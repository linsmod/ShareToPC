package github.linsmod.sharetopc.ui;

import android.os.Handler;
import android.os.Looper;

import androidx.lifecycle.ViewModel;

import com.google.gson.Gson;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import github.linsmod.sharetopc.App;
import github.linsmod.sharetopc.discovery.DiscoveredDevice;
import github.linsmod.sharetopc.transfer.EtpClient;

/**
 * ETP 文件浏览 ViewModel。
 * 打开时在常用位置按分类搜索常见类型并缓存结果；无缓存时对外发出
 * Initializing 进度（用于"正在初始化缓存"界面）。结果缓存到
 * SharedPreferences（磁盘）+ 内存，Activity 重建不重复下载。
 */
public class EtpExplorerViewModel extends ViewModel {

    /** 每个分类最多缓存条数（控制加载耗时与缓存体积）。 */
    private static final int CATEGORY_LIMIT = 100;
    /** 缓存前缀，实际 key 为 {@code prefix + deviceId}（无 deviceId 时回退 host）。 */
    private static final String CACHE_KEY_PREFIX = "etp_explorer_cache_";

    private static final int COLUMNS = EtpClient.SIZE_COLUMN | EtpClient.PATH_COLUMN
            | EtpClient.DATE_MODIFIED_COLUMN | EtpClient.DATE_CREATED_COLUMN
            | EtpClient.ATTRIBUTES_COLUMN;

    private final Gson gson = new Gson();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicReference<EtpClient> activeClient = new AtomicReference<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile boolean cancelled;

    private String savedDeviceId;
    private String savedHost;
    private int savedPort;

    private Consumer<State> observer;

    // 内存缓存（进程级，Activity 重建不丢）
    private List<CategoryResult> cachedData;
    private boolean loaded;
    private long lastCachedAt;

    // 进度快照（Activity 重建后恢复）
    private boolean progressFirstLoad;
    private int progressCurrent;
    private int progressTotal;
    private String progressLabel;

    // ==================== 状态 ====================

    public abstract static class State {
        private State() {}
    }

    /** 正在初始化/刷新缓存。current/total 为 0 时表示尚未开始（无进度细节）。 */
    public static final class Initializing extends State {
        public final boolean firstLoad;
        public final int current;
        public final int total;
        public final String categoryLabel;

        public Initializing(boolean firstLoad, int current, int total, String categoryLabel) {
            this.firstLoad = firstLoad;
            this.current = current;
            this.total = total;
            this.categoryLabel = categoryLabel;
        }
    }

    /** 分类结果就绪（来自缓存或刚下载完成）。 */
    public static final class Ready extends State {
        public final List<CategoryResult> categories;
        public final long cachedAt;
        public final boolean fromCache;

        public Ready(List<CategoryResult> categories, long cachedAt, boolean fromCache) {
            this.categories = categories;
            this.cachedAt = cachedAt;
            this.fromCache = fromCache;
        }
    }

    /** 加载失败。 */
    public static final class Error extends State {
        public final String message;

        public Error(String message) { this.message = message; }
    }

    // ==================== 数据模型 ====================

    /** 一个分类的搜索结果（可被 Gson 序列化）。 */
    public static class CategoryResult {
        public String label;
        public String pattern;
        public List<EtpClient.EtpSearchResult> items = new ArrayList<>();

        public CategoryResult() {}

        public CategoryResult(String label, String pattern) {
            this.label = label;
            this.pattern = pattern;
        }
    }

    /** 磁盘缓存结构。 */
    public static class ExplorerCache {
        public String host;
        public int port;
        public long cachedAt;
        public List<CategoryResult> categories = new ArrayList<>();
    }

    // ==================== 观察者 ====================

    public void observe(Consumer<State> observer) {
        this.observer = observer;
        if (running.get()) {
            emit(new Initializing(progressFirstLoad, progressCurrent, progressTotal, progressLabel));
        } else if (loaded && cachedData != null) {
            emit(new Ready(new ArrayList<>(cachedData), lastCachedAt, true));
        }
    }

    public void removeObserver() {
        this.observer = null;
    }

    private void emit(State state) {
        if (observer != null) {
            mainHandler.post(() -> observer.accept(state));
        }
    }

    // ==================== 初始化 / 刷新 ====================

    /** 打开页面入口：有缓存直接就绪，否则开始初始化缓存。 */
    public void init(String deviceId, String host, int port) {
        this.savedDeviceId = deviceId;
        this.savedHost = host;
        this.savedPort = port;
        ExplorerCache cache = readCache();
        if (cache != null && host.equals(cache.host) && cache.categories != null
                && !cache.categories.isEmpty()) {
            loaded = true;
            lastCachedAt = cache.cachedAt;
            cachedData = new ArrayList<>(cache.categories);
            if (observer != null) {
                emit(new Ready(new ArrayList<>(cachedData), lastCachedAt, true));
            }
        } else {
            startLoad(true);
        }
    }

    /** 手动刷新：重跑全部分类查询并更新缓存。 */
    public void refresh() {
        startLoad(false);
    }

    private void startLoad(boolean firstLoad) {
        if (running.get()) return;
        running.set(true);
        cancelled = false;
        progressFirstLoad = firstLoad;
        progressCurrent = 0;
        progressTotal = 0;
        progressLabel = null;
        emit(new Initializing(firstLoad, 0, 0, null));
        App.get().ioExecutor.execute(() -> loadOnBackground(firstLoad));
    }

    // ==================== 后台加载 ====================

    /** 按 deviceId 从设备库解析最新连接地址（host + ETP 端口）；无记录时回退 init 传入地址。 */
    private String[] resolveTarget() {
        if (savedDeviceId != null && !savedDeviceId.isEmpty()) {
            DiscoveredDevice d = App.get().deviceStore.getById(savedDeviceId);
            if (d != null && d.host != null && !d.host.isEmpty() && d.etpPort > 0) {
                return new String[]{d.host, String.valueOf(d.etpPort)};
            }
        }
        return new String[]{savedHost, String.valueOf(savedPort)};
    }

    private void loadOnBackground(boolean firstLoad) {
        EtpClient client = null;
        try {
            List<EtpSettingsActivity.PathPresets.QuickSearchCategory> categories = buildCategories();
            List<String> paths = getCheckedPaths();
            List<String> excludes = getExcludes();
            EtpSettingsActivity.PathPresets presets = loadPresets();
            progressTotal = categories.size();

            String[] x = resolveTarget();
            EtpClient c = new EtpClient(x[0], Integer.parseInt(x[1]));
            activeClient.set(c);
            if (cancelled) {
                activeClient.compareAndSet(c, null);
                closeQuietly(c);
                return;
            }
            c.connect();
            c.login("anonymous", "");
            if (!c.isLoggedIn()) c.login("anonymous", "");
            if (!c.isLoggedIn()) {
                throw new IOException("登录失败，请检查 ETP 服务是否开启");
            }
            client = c;

            List<CategoryResult> results = new ArrayList<>();
            for (int i = 0; i < categories.size(); i++) {
                if (cancelled) break;
                EtpSettingsActivity.PathPresets.QuickSearchCategory cat = categories.get(i);
                progressCurrent = i + 1;
                progressLabel = cat.label;
                emit(new Initializing(firstLoad, i + 1, categories.size(), cat.label));

                String query = buildQuery(cat.pattern, paths, excludes);
                EtpClient.QueryResult qr = c.query(query, COLUMNS,
                        presets.matchCase, presets.matchPath, presets.matchRegex,
                        presets.matchWholeWord, null, true, 0, CATEGORY_LIMIT);
                if (cancelled) break;
                CategoryResult cr = new CategoryResult(cat.label, cat.pattern);
                if (qr.items != null) cr.items.addAll(qr.items);
                results.add(cr);
            }

            if (cancelled) {
                activeClient.compareAndSet(client, null);
                closeQuietly(client);
                return;
            }

            ExplorerCache cache = new ExplorerCache();
            cache.host = x[0];
            cache.port = Integer.parseInt(x[1]);
            cache.cachedAt = System.currentTimeMillis();
            cache.categories = results;
            App.get().settingsStore.putString(cacheKey(), gson.toJson(cache));

            running.set(false);
            loaded = true;
            lastCachedAt = cache.cachedAt;
            cachedData = new ArrayList<>(results);
            emit(new Ready(new ArrayList<>(results), lastCachedAt, false));
        } catch (IOException e) {
            running.set(false);
            emit(new Error("加载失败: " + e.getMessage()));
        } finally {
            if (client != null) {
                activeClient.compareAndSet(client, null);
                closeQuietly(client);
            }
        }
    }

    /** 构造 Everything 查询：`位置1 | 位置2 分类模式 !排除1 !排除2`，与搜索页 buildPathFilter 一致。 */
    private static String buildQuery(String pattern, List<String> paths, List<String> excludes) {
        StringBuilder sb = new StringBuilder();
        if (!paths.isEmpty()) {
            for (int i = 0; i < paths.size(); i++) {
                if (i > 0) sb.append(" | ");
                sb.append(paths.get(i));
            }
            sb.append(" ");
        }
        sb.append(pattern);
        for (String ex : excludes) {
            sb.append(" !").append(ex);
        }
        return sb.toString();
    }

    // ==================== 预设 / 缓存 ====================

    /** 分类取自设置页"快速检索"；为空时回退到内置默认分类。 */
    private static List<EtpSettingsActivity.PathPresets.QuickSearchCategory> buildCategories() {
        EtpSettingsActivity.PathPresets presets = loadPresets();
        List<EtpSettingsActivity.PathPresets.QuickSearchCategory> items = EtpSettingsActivity.PathPresets.parseQuickSearches(presets.quickSearchText);
        if (!items.isEmpty()) return items;

        String[] labels = {"图片", "视频", "音频", "文档", "压缩包", "安装程序"};
        String[] patterns = {"image:", "video: size:>10mb", "audio:",
                "ext:doc | ext:docx | ext:pdf | ext:txt",
                "ext:zip | ext:rar | ext:7z | ext:tar.gz",
                "ext:exe | ext:msi"};
        List<EtpSettingsActivity.PathPresets.QuickSearchCategory> fallback = new ArrayList<>();
        for (int i = 0; i < labels.length; i++) {
            int iconRes = EtpSettingsActivity.PathPresets.iconForLabel(labels[i]);
            int colorRes = EtpSettingsActivity.PathPresets.colorForLabel(labels[i]);
            fallback.add(new EtpSettingsActivity.PathPresets.QuickSearchCategory(labels[i], patterns[i], iconRes, colorRes));
        }
        return fallback;
    }

    /** 当前勾选中的搜索位置（空 → 搜索整个磁盘）。 */
    private static List<String> getCheckedPaths() {
        List<String> paths = new ArrayList<>();
        EtpSettingsActivity.PathPresets presets = loadPresets();
        if (presets.paths != null) {
            for (EtpSettingsActivity.PathEntry e : presets.paths) {
                if (e.checked) {
                    String t = e.path.trim();
                    if (!t.isEmpty()) paths.add(t);
                }
            }
        }
        return paths;
    }

    /** 排除列表（仅设置中开启"使用排除列表"时生效）。 */
    private static List<String> getExcludes() {
        EtpSettingsActivity.PathPresets presets = loadPresets();
        if (presets.excludeEnabled && presets.excludeText != null
                && !presets.excludeText.trim().isEmpty()) {
            return EtpSettingsActivity.PathPresets.parseExcludes(presets.excludeText);
        }
        return new ArrayList<>();
    }

    static EtpSettingsActivity.PathPresets loadPresets() {
        String json = App.get().settingsStore.getString("etp_path_presets", null);
        if (json != null) {
            try {
                EtpSettingsActivity.PathPresets p = new Gson().fromJson(json, EtpSettingsActivity.PathPresets.class);
                if (p != null) return p;
            } catch (Exception ignored) {}
        }
        return EtpSettingsActivity.PathPresets.defaults();
    }

    /** 按设备缓存：优先 deviceId，缺失时回退 host（兼容无 ID 的旧设备/手动设备）。 */
    private String cacheKey() {
        String id = savedDeviceId;
        if (id == null || id.trim().isEmpty()) {
            id = savedHost != null ? savedHost : "";
        }
        return CACHE_KEY_PREFIX + id;
    }

    private ExplorerCache readCache() {
        String json = App.get().settingsStore.getString(cacheKey(), null);
        if (json == null || json.isEmpty()) return null;
        try {
            return gson.fromJson(json, ExplorerCache.class);
        } catch (Exception ignored) {
            return null;
        }
    }

    /** 供 UI 获取非空分类（与适配器分节顺序一致）。 */
    public static List<CategoryResult> nonEmpty(List<CategoryResult> categories) {
        List<CategoryResult> out = new ArrayList<>();
        if (categories != null) {
            for (CategoryResult c : categories) {
                if (c.items != null && !c.items.isEmpty()) out.add(c);
            }
        }
        return out;
    }

    /**
     * 基于缓存数据应用 分类选中 + 关键字 + 排序 生成展示列表。
     * selectedLabels 为空表示不筛选分类（显示全部）；query 为空表示不搜索；
     * sortName 为空表示保持缓存原始顺序。
     */
    public static List<CategoryResult> applyView(List<CategoryResult> all,
            Set<String> selectedLabels, String query,
            String sortName, boolean sortAsc) {
        List<CategoryResult> out = new ArrayList<>();
        if (all == null) return out;
        List<String> terms = parseTerms(query);
        for (CategoryResult c : all) {
            if (selectedLabels != null && !selectedLabels.isEmpty()
                    && !selectedLabels.contains(c.label)) {
                continue;
            }
            List<EtpClient.EtpSearchResult> items = filterItems(c.items, terms);
            if (items.isEmpty()) continue;
            sortItems(items, sortName, sortAsc, terms);
            CategoryResult copy = new CategoryResult(c.label, c.pattern);
            copy.items = items;
            out.add(copy);
        }
        return out;
    }

    /** 空格拆分关键字（小写）；空/纯空白返回空列表（不过滤）。 */
    static List<String> parseTerms(String query) {
        List<String> terms = new ArrayList<>();
        if (query == null) return terms;
        for (String t : query.trim().split("\\s+")) {
            if (!t.isEmpty()) terms.add(t.toLowerCase());
        }
        return terms;
    }

    /** 匹配关键字数：全部命中 = AND 结果，部分命中 = OR 结果；至少命中一个才保留。 */
    static int matchCount(EtpClient.EtpSearchResult it, List<String> terms) {
        if (terms == null || terms.isEmpty()) return 1;
        String name = str(it.name).toLowerCase();
        String path = str(it.fullPath()).toLowerCase();
        int count = 0;
        for (String t : terms) {
            if (name.contains(t) || path.contains(t)) count++;
        }
        return count;
    }

    private static List<EtpClient.EtpSearchResult> filterItems(
            List<EtpClient.EtpSearchResult> items, List<String> terms) {
        List<EtpClient.EtpSearchResult> out = new ArrayList<>();
        if (items == null) return out;
        for (EtpClient.EtpSearchResult it : items) {
            if (matchCount(it, terms) > 0) out.add(it);
        }
        return out;
    }

    /** 排序：先按关键字匹配数降序（AND 结果在前，OR 结果靠后），再按所选字段。 */
    private static void sortItems(List<EtpClient.EtpSearchResult> items,
                                  String sortName, boolean sortAsc, List<String> terms) {
        if (items == null) return;
        Comparator<EtpClient.EtpSearchResult> fieldComp = comparatorFor(sortName);
        if (!sortAsc && fieldComp != null) fieldComp = fieldComp.reversed();
        Comparator<EtpClient.EtpSearchResult> comp;
        if (fieldComp == null) {
            comp = (a, b) -> Integer.compare(matchCount(b, terms), matchCount(a, terms));
        } else {
            comp = Comparator
                    .comparingInt((EtpClient.EtpSearchResult a) -> -matchCount(a, terms))
                    .thenComparing(fieldComp);
        }
        items.sort(comp);
    }

    private static Comparator<EtpClient.EtpSearchResult> comparatorFor(String sortName) {
        if (sortName == null) return null;
        switch (sortName) {
            case "name": return (a, b) -> cmpStr(str(a.name), str(b.name));
            case "size": return (a, b) -> {
                int c = Long.compare(a.size, b.size);
                return c != 0 ? c : cmpStr(str(a.name), str(b.name));
            };
            case "path": return (a, b) -> cmpStr(str(a.fullPath()), str(b.fullPath()));
            case "extension": return (a, b) -> {
                int c = cmpStr(extension(a.name), extension(b.name));
                return c != 0 ? c : cmpStr(str(a.name), str(b.name));
            };
            case "date_modified": return Comparator.comparingLong(a -> a.dateModified);
            case "date_created": return Comparator.comparingLong(a -> a.dateCreated);
            default: return null;
        }
    }

    private static String str(String s) { return s != null ? s : ""; }

    private static String extension(String name) {
        if (name == null) return "";
        int i = name.lastIndexOf('.');
        return (i >= 0 && i < name.length() - 1) ? name.substring(i + 1).toLowerCase() : "";
    }

    private static int cmpStr(String a, String b) {
        return String.CASE_INSENSITIVE_ORDER.compare(a, b);
    }

    // ==================== 生命周期 ====================

    @Override
    protected void onCleared() {
        cancelled = true;
        running.set(false);
        EtpClient c = activeClient.getAndSet(null);
        if (c != null) {
            try {
                c.close();
            } catch (Exception ignored) {}
        }
        super.onCleared();
    }

    private static void closeQuietly(EtpClient client) {
        if (client != null) {
            try {
                client.close();
            } catch (Exception ignored) {}
        }
    }
}
