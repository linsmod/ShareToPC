package github.linsmod.sharetopc.ui;

import android.os.Handler;
import android.os.Looper;

import androidx.lifecycle.ViewModel;

import com.google.gson.Gson;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import github.linsmod.sharetopc.App;
import github.linsmod.sharetopc.transfer.EtpClient;

/**
 * ETP 文件浏览 ViewModel。
 * 打开时在常用位置按分类搜索常见类型并缓存结果；无缓存时对外发出
 * Initializing 进度（用于"正在初始化缓存"界面）。结果缓存到
 * SharedPreferences（磁盘）+ 内存，Activity 重建不重复拉取。
 */
public class EtpExplorerViewModel extends ViewModel {

    /** 每个分类最多缓存条数（控制加载耗时与缓存体积）。 */
    private static final int CATEGORY_LIMIT = 100;
    private static final String CACHE_KEY = "etp_explorer_cache";

    private static final int COLUMNS = EtpClient.SIZE_COLUMN | EtpClient.PATH_COLUMN
            | EtpClient.DATE_MODIFIED_COLUMN | EtpClient.DATE_CREATED_COLUMN
            | EtpClient.ATTRIBUTES_COLUMN;

    private final Gson gson = new Gson();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicReference<EtpClient> activeClient = new AtomicReference<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile boolean cancelled;

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

    /** 分类结果就绪（来自缓存或刚拉取完成）。 */
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
    public void init(String host, int port) {
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

    private void loadOnBackground(boolean firstLoad) {
        EtpClient client = null;
        try {
            List<String[]> categories = buildCategories();
            List<String> paths = getCheckedPaths();
            List<String> excludes = getExcludes();
            EtpSettingsActivity.PathPresets presets = loadPresets();
            progressTotal = categories.size();

            EtpClient c = new EtpClient(savedHost, savedPort);
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
                String[] cat = categories.get(i);
                progressCurrent = i + 1;
                progressLabel = cat[0];
                emit(new Initializing(firstLoad, i + 1, categories.size(), cat[0]));

                String query = buildQuery(cat[1], paths, excludes);
                EtpClient.QueryResult qr = c.query(query, COLUMNS,
                        presets.matchCase, presets.matchPath, presets.matchRegex,
                        presets.matchWholeWord, null, true, 0, CATEGORY_LIMIT);
                if (cancelled) break;
                CategoryResult cr = new CategoryResult(cat[0], cat[1]);
                if (qr.items != null) cr.items.addAll(qr.items);
                results.add(cr);
            }

            if (cancelled) {
                activeClient.compareAndSet(client, null);
                closeQuietly(client);
                return;
            }

            ExplorerCache cache = new ExplorerCache();
            cache.host = savedHost;
            cache.port = savedPort;
            cache.cachedAt = System.currentTimeMillis();
            cache.categories = results;
            App.get().settingsStore.putString(CACHE_KEY, gson.toJson(cache));

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

    /** 构造 Everything 查询：`(位置1 | 位置2) (分类模式) !排除1 !排除2`。 */
    private static String buildQuery(String pattern, List<String> paths, List<String> excludes) {
        StringBuilder sb = new StringBuilder();
        if (!paths.isEmpty()) {
            sb.append("(");
            for (int i = 0; i < paths.size(); i++) {
                if (i > 0) sb.append(" | ");
                sb.append(paths.get(i));
            }
            sb.append(") ");
        }
        sb.append("(").append(pattern).append(")");
        for (String ex : excludes) {
            sb.append(" !").append(ex);
        }
        return sb.toString();
    }

    // ==================== 预设 / 缓存 ====================

    /** 分类取自设置页"快速检索"；为空时回退到内置默认分类。 */
    private static List<String[]> buildCategories() {
        EtpSettingsActivity.PathPresets presets = loadPresets();
        List<String[]> items = EtpSettingsActivity.PathPresets.parseQuickSearches(presets.quickSearchText);
        if (!items.isEmpty()) return items;

        String[] labels = {"图片", "视频", "音频", "文档", "压缩包", "安装程序"};
        String[] patterns = {"image:", "video:", "audio:",
                "ext:doc | ext:docx | ext:pdf | ext:txt",
                "ext:zip | ext:rar | ext:7z | ext:tar.gz",
                "ext:exe | ext:msi"};
        List<String[]> fallback = new ArrayList<>();
        for (int i = 0; i < labels.length; i++) {
            fallback.add(new String[]{labels[i], patterns[i]});
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

    private static EtpSettingsActivity.PathPresets loadPresets() {
        String json = App.get().settingsStore.getString("etp_path_presets", null);
        if (json != null) {
            try {
                EtpSettingsActivity.PathPresets p = new Gson().fromJson(json, EtpSettingsActivity.PathPresets.class);
                if (p != null) return p;
            } catch (Exception ignored) {}
        }
        return EtpSettingsActivity.PathPresets.defaults();
    }

    private ExplorerCache readCache() {
        String json = App.get().settingsStore.getString(CACHE_KEY, null);
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
