package github.linsmod.sharetopc.ui;

import android.os.Handler;
import android.os.Looper;

import androidx.lifecycle.ViewModel;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import github.linsmod.sharetopc.App;
import github.linsmod.sharetopc.transfer.DeviceUserInfo;
import github.linsmod.sharetopc.transfer.EtpClient;
import github.linsmod.sharetopc.transfer.RemoteDrive;

/**
 * ETP 按位置逐级浏览 ViewModel。
 * 空路径栈 = 根视图（此电脑，列出磁盘）；进入目录用 Everything {@code parent:"<path>"}
 * 查询直接子项（文件夹、文件分开拉取以保证"文件夹优先 + 各自按名排序"的正确分页）。
 * 每个目录结果缓存到内存（按路径），返回/再次进入秒开；刷新只重查当前目录。
 */
public class EtpBrowseViewModel extends ViewModel {

    /** 每个分组（文件夹 / 文件）每批拉取条数。 */
    static final int PAGE_SIZE = 200;

    private static final int COLUMNS = EtpClient.SIZE_COLUMN | EtpClient.PATH_COLUMN
            | EtpClient.DATE_MODIFIED_COLUMN | EtpClient.ATTRIBUTES_COLUMN;

    /** 磁盘行显示名模板。 */
    static final String DRIVE_LABEL_FORMAT = "本地磁盘 (%s)";

    /** 常用位置：{上报 key, 显示标签, Windows 文件夹名}。 */
    private static final String[][] KNOWN_FOLDERS = {
            {"desktop", "桌面", "Desktop"},
            {"documents", "文档", "Documents"},
            {"downloads", "下载", "Downloads"},
            {"videos", "视频", "Videos"},
            {"pictures", "图片", "Pictures"},
            {"music", "音乐", "Music"},
    };

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicReference<EtpClient> activeClient = new AtomicReference<>();

    private String savedHost;
    private int savedPort;

    private Consumer<State> observer;

    // 路径栈（空 = 根视图）；元素为完整路径，栈顶即当前目录
    private final List<String> stack = new ArrayList<>();

    // 每个目录的已加载结果（仅主线程修改）
    private final Map<String, CachedFolder> folderCache = new HashMap<>();

    private State lastState;
    /** 任务令牌：每次发起新任务自增，用于丢弃过期回调。 */
    private int taskToken;
    /** 是否有任务进行中（仅主线程读写）。 */
    private boolean running;

    // 根视图常用位置
    private List<QuickLocation> quickLocations = new ArrayList<>();
    private boolean quickResolving;
    /** 是否已用服务器查询解析过（PC 端未上报真实目录时的回退）。 */
    private boolean serverResolved;
    /** 上次解析所用的 PC 上报映射快照（检测上报更新后重新解析）。 */
    private Map<String, String> lastReported;
    /** 服务器 root: 解析出的真实磁盘（PC 端未上报磁盘时的回退）。 */
    private List<DriveItem> driveFallback;

    // ==================== 状态 ====================

    public abstract static class State {
        private State() {}
    }

    /** 根视图：磁盘列表 + 常用位置快捷入口。 */
    public static final class Root extends State {
        public final List<DriveItem> drives;
        public final List<QuickLocation> quickLocations;

        public Root(List<DriveItem> drives, List<QuickLocation> quickLocations) {
            this.drives = drives;
            this.quickLocations = quickLocations;
        }
    }

    /** 正在加载目录。 */
    public static final class Loading extends State {
        public final String path;

        public Loading(String path) { this.path = path; }
    }

    /** 目录内容就绪（含已加载的多页合并结果）。 */
    public static final class Folder extends State {
        public final String path;
        public final List<EtpClient.EtpSearchResult> items;
        public final long totalCount;
        public final boolean hasMore;
        public final boolean fromCache;

        public Folder(String path, List<EtpClient.EtpSearchResult> items,
                      long totalCount, boolean hasMore, boolean fromCache) {
            this.path = path;
            this.items = items;
            this.totalCount = totalCount;
            this.hasMore = hasMore;
            this.fromCache = fromCache;
        }
    }

    /** 加载更多：进行中 / 失败（footer 状态）。 */
    public static final class LoadMoreState extends State {
        public final boolean loading;
        public final boolean failed;

        public LoadMoreState(boolean loading, boolean failed) {
            this.loading = loading;
            this.failed = failed;
        }
    }

    /** 加载失败。 */
    public static final class Error extends State {
        public final String message;

        public Error(String message) { this.message = message; }
    }

    /** 根视图中的一个磁盘项。 */
    public static class DriveItem {
        public final String label;
        public final String path;

        public DriveItem(String label, String path) {
            this.label = label;
            this.path = path;
        }
    }

    /** 常用位置快捷入口（从服务器解析出的具体路径）。 */
    public static class QuickLocation {
        public final String label;
        public final String path;

        public QuickLocation(String label, String path) {
            this.label = label;
            this.path = path;
        }
    }

    /** 一个目录的分组缓存（文件夹在前、文件在后，各自分页）。 */
    private static class CachedFolder {
        final List<EtpClient.EtpSearchResult> folders = new ArrayList<>();
        final List<EtpClient.EtpSearchResult> files = new ArrayList<>();
        long folderTotal;
        long fileTotal;
        boolean loadingMore;

        long total() { return folders.size() + files.size(); }

        boolean hasMore() {
            return folders.size() < folderTotal || files.size() < fileTotal;
        }

        List<EtpClient.EtpSearchResult> combined() {
            List<EtpClient.EtpSearchResult> out = new ArrayList<>(folders.size() + files.size());
            out.addAll(folders);
            out.addAll(files);
            return out;
        }
    }

    // ==================== 观察者 ====================

    public void observe(Consumer<State> observer) {
        this.observer = observer;
        if (lastState != null) emit(lastState);
    }

    public void removeObserver() {
        this.observer = null;
    }

    /** 最近一次状态快照（模式切换时用于立即渲染）。 */
    public State snapshot() {
        return lastState;
    }

    private void emit(State state) {
        lastState = state;
        if (observer != null) {
            mainHandler.post(() -> observer.accept(state));
        }
    }

    // ==================== 初始化 / 导航 ====================

    /** 打开页面入口：立即发出根视图（磁盘来自设置路径，本地计算无需网络）。 */
    public void init(String host, int port) {
        this.savedHost = host;
        this.savedPort = port;
        DeviceUserInfo.addListener(infoListener);
        emitRoot();
        resolveQuickLocations();
    }

    /** HELLO 上报更新（用户名/用户目录/磁盘）后刷新根视图；仅主线程回调。 */
    private final DeviceUserInfo.Listener infoListener = host -> {
        if (savedHost == null || !savedHost.equals(host)) return;
        if (stack.isEmpty()) emitRoot();
        resolveQuickLocations();
    };

    private void emitRoot() {
        lastState = new Root(buildDrives(), quickLocations);
        emit(lastState);
    }

    /**
     * 解析根视图"常用位置"。
     * 优先用 PC 端 HELLO 握手上报的真实目录（Windows SHGetKnownFolderPath，含 OneDrive 重定向）；
     * PC 端未上报（旧版本）时回退服务器正则解析：用上报的用户名精确定位，无用户名则泛型匹配
     * 并跳过 Public/Default 等系统用户目录。
     */
    private void resolveQuickLocations() {
        if (quickResolving) return;
        Map<String, String> reported = DeviceUserInfo.getUserFolders(savedHost);
        if (reported != null && reported.equals(lastReported)) return;

        List<QuickLocation> direct = buildFromReported(reported);
        if (!direct.isEmpty()) {
            lastReported = reported;
            quickLocations = direct;
            if (stack.isEmpty()) emitRoot();
            return;
        }
        if (serverResolved) return;
        serverResolve();
    }

    /** 用 PC 上报的真实目录直接构造快捷位置（无上报或全为空 → 空列表）。 */
    private static List<QuickLocation> buildFromReported(Map<String, String> reported) {
        List<QuickLocation> out = new ArrayList<>();
        if (reported == null || reported.isEmpty()) return out;
        for (String[] f : KNOWN_FOLDERS) {
            String p = reported.get(f[0]);
            if (p != null && !p.trim().isEmpty()) {
                out.add(new QuickLocation(f[1], p));
            }
        }
        return out;
    }

    /** 服务器回退解析（一次性；完成后延迟重查一次以接住刚就绪的 PC 上报）。 */
    private void serverResolve() {
        String user = DeviceUserInfo.getUserName(savedHost);
        quickResolving = true;
        final boolean usingUser = user != null && !user.isEmpty();
        final String userForQuery = usingUser ? user : null;
        App.get().ioExecutor.execute(() -> {
            EtpClient c = new EtpClient(savedHost, savedPort);
            try {
                c.connect();
                c.login("anonymous", "");
                if (!c.isLoggedIn()) c.login("anonymous", "");
                if (!c.isLoggedIn()) throw new IOException("登录失败，请检查 ETP 服务是否开启");
                EtpClient.QueryResult foldersQr = c.query(buildKnownFoldersQuery(userForQuery), COLUMNS,
                        0, 0, 0, 0, null, true, 0, 0);
                EtpClient.QueryResult rootQr = c.query("root:", COLUMNS,
                        0, 0, 0, 0, "name", true, 0, 0);
                final List<QuickLocation> locations = parseKnownFolders(foldersQr.items);
                final List<DriveItem> drives = parseRootDrives(rootQr.items);
                mainHandler.post(() -> {
                    quickResolving = false;
                    serverResolved = true;
                    quickLocations = locations;
                    if (!drives.isEmpty()) driveFallback = drives;
                    if (stack.isEmpty()) emitRoot();
                    // HELLO 上报可能刚就绪：延迟重查一次（无上报时 resolveQuickLocations 会直接返回）
                    mainHandler.postDelayed(this::resolveQuickLocations, 2000);
                });
            } catch (IOException e) {
                mainHandler.post(() -> {
                    quickResolving = false;
                    serverResolved = true;
                });
            } finally {
                closeQuietly(c);
            }
        });
    }

    /**
     * 常用位置查询：folder: 限定文件夹，path:regex 匹配各用户目录本身。
     * userName 非空时用精确用户名（不再猜用户名部分），否则泛型匹配任意用户名。
     */
    private static String buildKnownFoldersQuery(String userName) {
        boolean useUser = userName != null && !userName.isEmpty();
        String userRegex = useUser ? java.util.regex.Pattern.quote(userName) : "[^\\\\]+";
        StringBuilder sb = new StringBuilder("folder:");
        for (int i = 0; i < KNOWN_FOLDERS.length; i++) {
            String folder = KNOWN_FOLDERS[i][2];
            sb.append(i == 0 ? " " : " | ")
                    .append("path:regex:[A-Z]:\\\\Users\\\\").append(userRegex)
                    .append("\\\\").append(folder).append("$");
        }
        return sb.toString();
    }

    /** 系统/默认用户目录（泛型匹配时需跳过，避免把 Public/Default 的桌面当作用户目录）。 */
    private static boolean isSystemUserDir(String path) {
        if (path == null) return false;
        String lower = path.toLowerCase(Locale.US);
        return lower.contains("\\users\\public\\")
                || lower.contains("\\users\\default\\")
                || lower.contains("\\users\\all users\\")
                || lower.contains("\\users\\default user\\");
    }

    /** 把查询结果按 KNOWN_FOLDERS 顺序映射为快捷位置（取每个文件夹的第一个匹配）。 */
    private static List<QuickLocation> parseKnownFolders(List<EtpClient.EtpSearchResult> items) {
        Map<String, EtpClient.EtpSearchResult> byName = new HashMap<>();
        if (items != null) {
            for (EtpClient.EtpSearchResult r : items) {
                if (isSystemUserDir(r.path)) continue;
                String n = str(r.name).toLowerCase(Locale.US);
                if (!byName.containsKey(n) && r.path != null && !r.path.isEmpty()) {
                    byName.put(n, r);
                }
            }
        }
        List<QuickLocation> out = new ArrayList<>();
        for (String[] f : KNOWN_FOLDERS) {
            EtpClient.EtpSearchResult r = byName.get(f[2].toLowerCase(Locale.US));
            if (r != null) {
                out.add(new QuickLocation(f[1], r.path + "\\" + r.name));
            }
        }
        return out;
    }

    public boolean isAtRoot() {
        return stack.isEmpty();
    }

    /** 当前目录路径（空 = 根视图）。 */
    public String getCurrentPath() {
        return stack.isEmpty() ? null : stack.get(stack.size() - 1);
    }

    /**
     * 打开一个绝对路径（如从"常用文件"点击文件夹进入）。
     * 会按路径分量重建路径栈，保证面包屑与返回路径正确。
     */
    public void openFolder(String fullPath) {
        stack.clear();
        stack.addAll(buildChain(fullPath));
        navigateCurrent();
    }

    public void openRoot() {
        stack.clear();
        emitRoot();
        // 用户名可能在 HELLO 握手后才上报，回到根视图时若已就绪则用精确用户名重新解析
        resolveQuickLocations();
    }

    /** 面包屑跳转：跳到第 index 个路径段（0 = 第一个，即磁盘）。 */
    public void openSegment(int index) {
        if (stack.isEmpty() || index < 0) return;
        while (stack.size() > index + 1) {
            stack.remove(stack.size() - 1);
        }
        navigateCurrent();
    }

    public void goUp() {
        if (stack.isEmpty()) return;
        stack.remove(stack.size() - 1);
        if (stack.isEmpty()) {
            emitRoot();
        } else {
            navigateCurrent();
        }
    }

    /** 刷新当前目录：清掉该目录缓存后重新拉取。 */
    public void refreshCurrent() {
        String path = getCurrentPath();
        if (path == null) {
            emitRoot();
            return;
        }
        folderCache.remove(path);
        cancelInFlight();
        startLoad(path);
    }

    /** 面包屑段标签（首段为 null，由 UI 显示"此电脑"）。 */
    public List<String> getBreadcrumbLabels() {
        List<String> labels = new ArrayList<>();
        labels.add(null);
        for (String p : stack) {
            labels.add(segmentLabel(p));
        }
        return labels;
    }

    private void navigateCurrent() {
        String path = getCurrentPath();
        if (path == null) {
            emitRoot();
            return;
        }
        CachedFolder cached = folderCache.get(path);
        if (cached != null) {
            emitFolder(path, cached, true);
            return;
        }
        cancelInFlight();
        startLoad(path);
    }

    /** 从绝对路径重建路径栈，如 "C:\Users\foo" → ["C:\", "C:\Users", "C:\Users\foo"]。 */
    static List<String> buildChain(String fullPath) {
        List<String> chain = new ArrayList<>();
        if (fullPath == null || fullPath.isEmpty()) return chain;

        String p = fullPath;
        int colon = p.indexOf(':');
        String drive;
        if (colon >= 0) {
            drive = p.substring(0, colon + 1);
            chain.add(drive + "\\");
            p = p.substring(drive.length());
        } else {
            chain.add(p);
            return chain;
        }

        StringBuilder cur = new StringBuilder(chain.get(0));
        for (String part : p.split("\\\\")) {
            if (part.isEmpty()) continue;
            cur.append(part);
            chain.add(cur.toString());
            cur.append("\\");
        }
        return chain;
    }

    /** 路径段显示名：去掉尾随反斜杠后取最后一段（"C:\" → "C:"）。 */
    static String segmentLabel(String path) {
        if (path == null) return null;
        String t = path;
        while (t.endsWith("\\")) {
            t = t.substring(0, t.length() - 1);
        }
        int i = t.lastIndexOf('\\');
        return i >= 0 ? t.substring(i + 1) : t;
    }

    /**
     * 根视图磁盘列表：优先用 PC 端 HELLO 上报的真实磁盘（含类型/卷标）；
     * 未上报时用服务器 root: 查询解析的真实磁盘；均不可得时为空（不再列出 A:-Z: 虚假盘）。
     */
    private List<DriveItem> buildDrives() {
        List<RemoteDrive> reported = DeviceUserInfo.getDrives(savedHost);
        if (reported != null && !reported.isEmpty()) {
            List<DriveItem> drives = new ArrayList<>();
            for (RemoteDrive d : reported) {
                if (d.name.isEmpty()) continue;
                String path = d.name.endsWith("\\") ? d.name : d.name + "\\";
                drives.add(new DriveItem(driveDisplayName(d), path));
            }
            return drives;
        }
        return driveFallback != null ? driveFallback : new ArrayList<>();
    }

    /** 磁盘显示名：有卷标用"卷标 (C:)"，否则按类型显示可移动磁盘/网络驱动器/光盘驱动器等。 */
    private static String driveDisplayName(RemoteDrive d) {
        if (d.label != null && !d.label.trim().isEmpty()) {
            return d.label.trim() + " (" + d.name + ")";
        }
        String prefix;
        String type = d.type != null ? d.type : "";
        switch (type) {
            case "Removable": prefix = "可移动磁盘"; break;
            case "Network":   prefix = "网络驱动器"; break;
            case "CDRom":     prefix = "光盘驱动器"; break;
            case "Ram":       prefix = "内存驱动器"; break;
            default:          prefix = "本地磁盘"; break;
        }
        return prefix + " (" + d.name + ")";
    }

    /** 从 Everything root: 查询结果解析真实磁盘（无父文件夹的项 = 磁盘根）。 */
    private static List<DriveItem> parseRootDrives(List<EtpClient.EtpSearchResult> items) {
        List<DriveItem> out = new ArrayList<>();
        if (items == null) return out;
        for (EtpClient.EtpSearchResult r : items) {
            if (!r.isFolder) continue;
            String p = r.fullPath();
            if (p == null || p.isEmpty() || p.length() < 2 || p.charAt(1) != ':') continue;
            if (!p.endsWith("\\")) p += "\\";
            String drive = p.substring(0, 2).toUpperCase(Locale.US);
            out.add(new DriveItem(String.format(Locale.US, DRIVE_LABEL_FORMAT, drive), p));
        }
        return out;
    }

    // ==================== 加载 ====================

    private void startLoad(String path) {
        if (running) return;
        running = true;
        int token = ++taskToken;
        emit(new Loading(path));

        App.get().ioExecutor.execute(() -> {
            EtpClient c = new EtpClient(savedHost, savedPort);
            activeClient.set(c);
            if (activeClient.get() != c) {
                closeQuietly(c);
                return;
            }
            try {
                c.connect();
                c.login("anonymous", "");
                if (!c.isLoggedIn()) c.login("anonymous", "");
                if (!c.isLoggedIn()) throw new IOException("登录失败，请检查 ETP 服务是否开启");
                EtpClient.QueryResult folders = c.queryChildren(path, COLUMNS,
                        0, 0, 0, 0, "name", true, 0, PAGE_SIZE, "folder:");
                EtpClient.QueryResult files = c.queryChildren(path, COLUMNS,
                        0, 0, 0, 0, "name", true, 0, PAGE_SIZE, "!folder:");
                mainHandler.post(() -> onFolderLoaded(token, path, folders, files, c));
            } catch (IOException e) {
                mainHandler.post(() -> onFolderFailed(token, c, e));
            }
        });
    }

    /** 加载更多：当前目录还有未拉取的子项时追加下一页（文件夹、文件各一页）。 */
    public void loadMore() {
        String path = getCurrentPath();
        if (path == null || running) return;
        CachedFolder cf = folderCache.get(path);
        if (cf == null || !cf.hasMore() || cf.loadingMore) return;

        cf.loadingMore = true;
        running = true;
        int token = ++taskToken;
        emit(new LoadMoreState(true, false));

        App.get().ioExecutor.execute(() -> {
            EtpClient c = new EtpClient(savedHost, savedPort);
            activeClient.set(c);
            if (activeClient.get() != c) {
                cf.loadingMore = false;
                closeQuietly(c);
                return;
            }
            try {
                c.connect();
                c.login("anonymous", "");
                if (!c.isLoggedIn()) c.login("anonymous", "");
                if (!c.isLoggedIn()) throw new IOException("登录失败，请检查 ETP 服务是否开启");
                int folderOffset = cf.folders.size();
                int fileOffset = cf.files.size();
                EtpClient.QueryResult folders = folderOffset < cf.folderTotal
                        ? c.queryChildren(path, COLUMNS, 0, 0, 0, 0, "name", true, folderOffset, PAGE_SIZE, "folder:")
                        : null;
                EtpClient.QueryResult files = fileOffset < cf.fileTotal
                        ? c.queryChildren(path, COLUMNS, 0, 0, 0, 0, "name", true, fileOffset, PAGE_SIZE, "!folder:")
                        : null;
                mainHandler.post(() -> onMoreLoaded(token, path, cf, folders, files, c));
            } catch (IOException e) {
                mainHandler.post(() -> onMoreFailed(token, cf, c));
            }
        });
    }

    // ---- 结果回调（主线程） ----

    private void onFolderLoaded(int token, String path,
                                EtpClient.QueryResult folders,
                                EtpClient.QueryResult files, EtpClient client) {
        try {
            if (token != taskToken || !path.equals(getCurrentPath())) return;

            CachedFolder cf = new CachedFolder();
            cf.folders.addAll(filterHidden(folders.items));
            cf.files.addAll(filterHidden(files.items));
            cf.folderTotal = totalFor(folders, cf.folders.size());
            cf.fileTotal = totalFor(files, cf.files.size());
            folderCache.put(path, cf);
            running = false;
            emitFolder(path, cf, false);
        } finally {
            finishQuery(client);
        }
    }

    private void onFolderFailed(int token, EtpClient client, IOException e) {
        try {
            if (token != taskToken) return;
            running = false;
            lastState = new Error("加载失败: " + e.getMessage());
            emit(lastState);
        } finally {
            finishQuery(client);
        }
    }

    private void onMoreLoaded(int token, String path, CachedFolder cf,
                              EtpClient.QueryResult folders,
                              EtpClient.QueryResult files, EtpClient client) {
        try {
            if (token != taskToken || !path.equals(getCurrentPath())) {
                cf.loadingMore = false;
                return;
            }
            if (folders != null) {
                cf.folders.addAll(filterHidden(folders.items));
                cf.folderTotal = totalFor(folders, cf.folders.size());
            }
            if (files != null) {
                cf.files.addAll(filterHidden(files.items));
                cf.fileTotal = totalFor(files, cf.files.size());
            }
            cf.loadingMore = false;
            running = false;
            emit(new LoadMoreState(false, false));
            emitFolder(path, cf, false);
        } finally {
            finishQuery(client);
        }
    }

    private void onMoreFailed(int token, CachedFolder cf, EtpClient client) {
        try {
            if (token != taskToken) {
                cf.loadingMore = false;
                return;
            }
            cf.loadingMore = false;
            running = false;
            lastState = new LoadMoreState(false, true);
            emit(lastState);
        } finally {
            finishQuery(client);
        }
    }

    private void emitFolder(String path, CachedFolder cf, boolean fromCache) {
        List<EtpClient.EtpSearchResult> items = cf.combined();
        lastState = new Folder(path, items, items.size(), cf.hasMore(), fromCache);
        emit(lastState);
    }

    /** 服务器未返回 RESULT_COUNT 时回退为已取到的条数。 */
    private static long totalFor(EtpClient.QueryResult qr, long fetchedSize) {
        return qr.resultCount > 0 ? qr.resultCount : fetchedSize;
    }

    /** 过滤隐藏/系统文件（属性位 0x02=Hidden, 0x04=System），未返回属性时不过滤。 */
    private static List<EtpClient.EtpSearchResult> filterHidden(
            List<EtpClient.EtpSearchResult> in) {
        List<EtpClient.EtpSearchResult> out = new ArrayList<>();
        if (in == null) return out;
        for (EtpClient.EtpSearchResult r : in) {
            if (r.attributes >= 0 && (r.attributes & (0x02 | 0x04)) != 0) continue;
            out.add(r);
        }
        return out;
    }

    private static String str(String s) {
        return s != null ? s : "";
    }

    // ==================== 任务控制 / 生命周期 ====================

    /** 取消进行中的任务：关闭连接、作废回调令牌、复位标志。 */
    private void cancelInFlight() {
        if (running) {
            running = false;
            taskToken++;
        }
        cancelActiveQuery();
        for (CachedFolder cf : folderCache.values()) {
            cf.loadingMore = false;
        }
    }

    private void cancelActiveQuery() {
        closeQuietly(activeClient.getAndSet(null));
    }

    private void finishQuery(EtpClient client) {
        activeClient.compareAndSet(client, null);
        closeQuietly(client);
    }

    private static void closeQuietly(EtpClient client) {
        if (client != null) {
            try {
                client.close();
            } catch (Exception ignored) {}
        }
    }

    @Override
    protected void onCleared() {
        taskToken++;
        cancelActiveQuery();
        DeviceUserInfo.removeListener(infoListener);
        super.onCleared();
    }
}
