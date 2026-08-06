package github.linsmod.sharetopc.ui;

import android.os.Handler;
import android.os.Looper;

import androidx.lifecycle.ViewModel;

import com.google.gson.Gson;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import github.linsmod.sharetopc.App;
import github.linsmod.sharetopc.transfer.EtpClient;

/**
 * ETP 搜索 ViewModel — 用 Consumer 回调代替 LiveData。
 * 管理连接、搜索、分页、历史，Activity 只负责渲染状态。
 */
public class EtpSearchViewModel extends ViewModel {

    /** 每批传输条数（列表底部"加载更多"一次下载的数量）。 */
    private static final int PAGE_SIZE = 50;
    private static final int MAX_HISTORY = 10;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean connecting;

    // 自动重连
    private String savedHost;
    private int savedPort;
    private String pendingQuery;
    private boolean verified; // 是否已成功连接验证过（探测性连接完成）

    // 观察者
    private Consumer<SearchUiState> stateObserver;

    // 搜索上下文
    private String currentQuery = "";
    private long resultCount = 0;
    private boolean hasMore = false;          // 服务器还有未传输的结果
    private boolean loadMoreLoading = false;  // 加载更多进行中（防重入）
    private int maxResultsLimit = 0;          // 设置中的结果总量上限，0=不限制

    // 累积结果（分页追加）
    private final List<EtpClient.EtpSearchResult> allResults = new ArrayList<>();

    // 当前进行中的查询连接（跨线程访问，AtomicReference 保证可见性）
    private final AtomicReference<EtpClient> activeClient = new AtomicReference<>();

    // 搜索历史（LinkedHashSet 做去重 + 保序）
    private final Set<String> searchHistory = new LinkedHashSet<>();

    // ==================== 状态回调 ====================

    /** 注册状态观察者，注册时立即回调当前状态。 */
    public void observe(Consumer<SearchUiState> observer) {
        this.stateObserver = observer;
        // 未连接且未在连接中 → 直接就绪，等待用户发起搜索才建立连接
        emit(connecting ? new SearchUiState.Connecting() : new SearchUiState.Ready());
    }

    /** 移除观察者（避免泄漏）。 */
    public void removeObserver() {
        this.stateObserver = null;
    }

    private void emit(SearchUiState state) {
        if (stateObserver != null) {
            mainHandler.post(() -> stateObserver.accept(state));
        }
    }

    // ==================== 连接 ====================

    /** 仅保存目标地址，不建立连接；首次搜索时才会真正连接。 */
    public void init(String host, int port) {
        this.savedHost = host;
        this.savedPort = port;
    }

    public void connect(String host, int port) {
        this.savedHost = host;
        this.savedPort = port;
        connecting = true;
        emit(new SearchUiState.Connecting());

        App.get().ioExecutor.execute(() -> {
            try (EtpClient c = new EtpClient(host, port)) {
                c.connect();
                c.login("anonymous", "");
                if (!c.isLoggedIn()) {
                    c.login("anonymous", "");
                }
                if (c.isLoggedIn()) {
                    App.get().settingsStore.setLastEtpHost(host);
                    App.get().settingsStore.setLastEtpPort(port);
                    connecting = false;
                    verified = true;

                    String pq = pendingQuery;
                    pendingQuery = null;
                    if (pq != null && !pq.isEmpty()) {
                        doQuery(pq, 0, false);
                    } else {
                        emit(new SearchUiState.Ready());
                    }
                } else {
                    connecting = false;
                    verified = false;
                    pendingQuery = null;
                    emit(new SearchUiState.Error("登录失败，请检查 ETP 服务是否开启"));
                }
            } catch (IOException e) {
                connecting = false;
                verified = false;
                pendingQuery = null;
                emit(new SearchUiState.Error("连接失败: " + e.getMessage()));
            }
        });
    }

    public boolean isConnected() {
        return verified && !connecting;
    }

    /** 使用上次保存的地址自动重连。 */
    private void reconnect() {
        if (savedHost == null || savedHost.isEmpty()) {
            emit(new SearchUiState.Error("未配置连接信息"));
            return;
        }
        connect(savedHost, savedPort);
    }

    // ==================== 搜索 ====================

    /** 执行新搜索，自动处理重连。 */
    public void search(String query) {
        if (query.isEmpty()) return;
        cancelActiveQuery(); // 取消并关闭上一个进行中的查询
        currentQuery = query;
        allResults.clear();
        hasMore = false;
        loadMoreLoading = false;
        maxResultsLimit = loadQueryParams().maxResults;
        emit(new SearchUiState.Searching(query));

        if (connecting) {
            // 正在连接中，挂起查询，连接成功后自动执行
            pendingQuery = query;
            return;
        }

        if (!isConnected()) {
            // 断连 → 自动重连
            pendingQuery = query;
            reconnect();
            return;
        }

        pendingQuery = null;
        doQuery(query, 0, false);
    }

    /** 撤销当前查询：关闭进行中的连接并清空查询状态（搜索页按返回取消时调用）。 */
    public void cancelQuery() {
        pendingQuery = null;
        currentQuery = "";
        allResults.clear();
        hasMore = false;
        loadMoreLoading = false;
        cancelActiveQuery(); // 关闭后后台线程请求失败会静默退出（query 不匹配）
    }

    /** 加载更多：从已显示条数开始下载下一批。 */
    public void loadMore() {
        if (!hasMore || loadMoreLoading || currentQuery.isEmpty()) return;
        if (!isConnected()) return;
        cancelActiveQuery(); // 取消并关闭上一个请求，防止连点堆积
        loadMoreLoading = true;
        emit(new SearchUiState.LoadMoreState(true, false));
        doQuery(currentQuery, allResults.size(), true);
    }

    private void doQuery(String query, int offset, boolean isLoadMore) {
        int columns = EtpClient.SIZE_COLUMN | EtpClient.PATH_COLUMN
                | EtpClient.DATE_MODIFIED_COLUMN | EtpClient.DATE_CREATED_COLUMN
                | EtpClient.ATTRIBUTES_COLUMN;

        String filteredQuery = buildPathFilter(query);
        QueryParams qp = loadQueryParams();

        // 每批最多 PAGE_SIZE 条；设置总量上限时不超过剩余额度
        int limit = maxResultsLimit;
        int count = limit > 0 ? Math.min(PAGE_SIZE, limit - offset) : PAGE_SIZE;

        App.get().ioExecutor.execute(() -> {
            EtpClient c = new EtpClient(savedHost, savedPort);
            activeClient.set(c); // 注册当前请求引用，供 cancelActiveQuery() 取消
            if (activeClient.get() != c) {
                closeQuietly(c); // 注册前已被取消，直接退出
                return;
            }
            try {
                c.connect();
                c.login("anonymous", "");
                EtpClient.QueryResult qr = c.query(filteredQuery, columns,
                        qp.matchCase, qp.matchPath, qp.matchRegex, qp.matchWholeWord,
                        qp.sortName, qp.sortAsc, offset, count);
                mainHandler.post(() -> onQueryResult(query, qr, c));
            } catch (IOException e) {
                mainHandler.post(() -> onQueryError(query, c, e, isLoadMore));
            }
        });
    }

    private void onQueryResult(String query, EtpClient.QueryResult qr, EtpClient client) {
        try {
            if (!query.equals(currentQuery)) return; // 过期结果，忽略

            allResults.addAll(qr.items);
            resultCount = qr.resultCount; // 服务器返回的真实匹配总数（RESULT_COUNT）
            loadMoreLoading = false;

            // 还有未传输的结果：服务器总数 > 已显示条数，且未达设置的上限
            hasMore = qr.resultCount > allResults.size()
                    && (maxResultsLimit <= 0 || allResults.size() < maxResultsLimit);

            if (allResults.isEmpty()) {
                emit(new SearchUiState.Empty(query, getQueryPathLabels()));
            } else {
                emit(new SearchUiState.HasResults(
                        new ArrayList<>(allResults), resultCount, hasMore, query));
            }
        } finally {
            finishQuery(client); // 查询生命周期已结束，无论结果是否被采用都关闭连接
        }
    }

    /** 查询失败（或被取消中断）：仅当前查询报错，过期/被取消的请求静默关闭。 */
    private void onQueryError(String query, EtpClient client, IOException e, boolean isLoadMore) {
        try {
            if (!query.equals(currentQuery)) return; // 已被取消/过期的请求，静默处理
            loadMoreLoading = false;
            if (isLoadMore) {
                // 加载更多失败：不打断结果页，footer 显示重试
                emit(new SearchUiState.LoadMoreState(false, true));
            } else {
                emit(new SearchUiState.Error("搜索出错: " + e.getMessage()));
            }
        } finally {
            finishQuery(client);
        }
    }

    /** 取消并关闭当前进行中的查询连接（幂等，无连接时无操作）。 */
    private void cancelActiveQuery() {
        closeQuietly(activeClient.getAndSet(null));
    }

    /** 查询结束收尾：若该连接仍是活跃请求则清空引用，然后关闭连接。 */
    private void finishQuery(EtpClient client) {
        activeClient.compareAndSet(client, null);
        closeQuietly(client);
    }

    private static void closeQuietly(EtpClient client) {
        if (client != null) {
            try {
                client.close();
            } catch (Exception ignored) {
            }
        }
    }

    // ==================== 路径过滤 ====================

    /** 从 SettingsStore 加载路径预设，失败时返回默认值。 */
    private EtpSettingsActivity.PathPresets loadPresets() {
        String json = App.get().settingsStore.getString("etp_path_presets", null);
        if (json != null) {
            try {
                EtpSettingsActivity.PathPresets p = new Gson().fromJson(json, EtpSettingsActivity.PathPresets.class);
                if (p != null) return p;
            } catch (Exception ignored) {}
        }
        return EtpSettingsActivity.PathPresets.defaults();
    }

    /** 构造 Everything 位置过滤：pathA | pathB | pathC {kw}，并附加结果排除（!expr）。 */
    public String buildPathFilter(String rawQuery) {
        EtpSettingsActivity.PathPresets presets = loadPresets();
        String base = rawQuery;

        if (presets.paths != null) {
            List<String> paths = new ArrayList<>();
            for (EtpSettingsActivity.PathEntry entry : presets.paths) {
                if (entry.checked) {
                    String trimmed = entry.path.trim();
                    if (!trimmed.isEmpty()) paths.add(trimmed);
                }
            }
            if (!paths.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < paths.size(); i++) {
                    if (i > 0) sb.append(" | ");
                    sb.append(paths.get(i));
                }
                sb.append(" ").append(rawQuery);
                base = sb.toString();
            }
        }

        // 结果排除：query + ' ' + (items.join(' !'))（仅搜索页开关开启时附加）
        if (presets.excludeEnabled) {
            List<String> excludes = EtpSettingsActivity.PathPresets.parseExcludes(presets.excludeText);
            if (!excludes.isEmpty()) {
                StringBuilder sb = new StringBuilder(base);
                for (String ex : excludes) {
                    sb.append(" !").append(ex);
                }
                base = sb.toString();
            }
        }
        return base;
    }

    /** 获取当前启用的搜索路径标签（用于空结果时展示）。 */
    public List<String> getQueryPathLabels() {
        EtpSettingsActivity.PathPresets presets = loadPresets();
        if (presets == null || presets.paths == null) return new ArrayList<>();

        List<String> labels = new ArrayList<>();
        for (EtpSettingsActivity.PathEntry entry : presets.paths) {
            if (entry.checked) {
                String trimmed = entry.label.trim();
                if (!trimmed.isEmpty()) labels.add(trimmed);
            }
        }
        return labels;
    }

    /** 获取所有路径项（含选中状态），用于渲染 checkbox 列表。 */
    public List<PathItem> getAllPathItems() {
        EtpSettingsActivity.PathPresets presets = loadPresets();
        if (presets == null || presets.paths == null) return new ArrayList<>();

        List<PathItem> items = new ArrayList<>();
        for (EtpSettingsActivity.PathEntry entry : presets.paths) {
            items.add(new PathItem(entry.path, entry.label, entry.type, entry.checked));
        }
        return items;
    }

    /** 切换单个路径的选中状态，保存并重新搜索。 */
    public void togglePath(String key, String type, boolean checked) {
        EtpSettingsActivity.PathPresets presets = loadPresets();
        if (presets == null || presets.paths == null) return;

        for (EtpSettingsActivity.PathEntry entry : presets.paths) {
            if (entry.path.equals(key)) {
                entry.checked = checked;
                break;
            }
        }

        String newJson = new Gson().toJson(presets);
        App.get().settingsStore.putString("etp_path_presets", newJson);

        // 重新搜索
        if (currentQuery != null && !currentQuery.isEmpty()) {
            search(currentQuery);
        }
    }

    /** 路径项数据（用于构建 checkbox 列表）。 */
    public static class PathItem {
        public final String key;
        public final String label;
        public final String type;
        public final boolean checked;

        public PathItem(String key, String label, String type, boolean checked) {
            this.key = key;
            this.label = label;
            this.type = type;
            this.checked = checked;
        }
    }

    /** 从预设中解析查询参数。 */
    private QueryParams loadQueryParams() {
        EtpSettingsActivity.PathPresets p = loadPresets();
        QueryParams qp = new QueryParams();
        if (p != null) {
            qp.matchCase = p.matchCase;
            qp.matchPath = p.matchPath;
            qp.matchRegex = p.matchRegex;
            qp.matchWholeWord = p.matchWholeWord;
            qp.sortName = (p.sortName != null && !p.sortName.isEmpty()) ? p.sortName : null;
            qp.sortAsc = p.sortAsc;
            qp.maxResults = Math.max(0, p.maxResults);
        }
        return qp;
    }

    private static class QueryParams {
        int matchCase, matchPath, matchRegex, matchWholeWord;
        String sortName;
        boolean sortAsc = true;
        int maxResults;
    }

    // ==================== 搜索历史 ====================

    private void loadSearchHistory() {
        String json = App.get().settingsStore.getString("etp_search_history", "");
        searchHistory.clear();
        if (!json.isEmpty()) {
            try {
                org.json.JSONArray arr = new org.json.JSONArray(json);
                for (int i = 0; i < arr.length(); i++) searchHistory.add(arr.getString(i));
            } catch (org.json.JSONException ignored) {}
        }
    }

    void addSearchHistory(String query) {
        searchHistory.remove(query);
        searchHistory.add(query);
        List<String> trimmed = new ArrayList<>(searchHistory);
        if (trimmed.size() > MAX_HISTORY) {
            searchHistory.clear();
            for (int i = trimmed.size() - MAX_HISTORY; i < trimmed.size(); i++) {
                searchHistory.add(trimmed.get(i));
            }
        }
        org.json.JSONArray arr = new org.json.JSONArray();
        List<String> list = new ArrayList<>(searchHistory);
        for (int i = list.size() - 1; i >= 0; i--) arr.put(list.get(i));
        App.get().settingsStore.putString("etp_search_history", arr.toString());
    }

    /** 获取反转后的历史列表（最近在前）。 */
    public List<String> getHistoryReversed() {
        if (searchHistory.isEmpty()) loadSearchHistory();
        List<String> reversed = new ArrayList<>(searchHistory);
        java.util.Collections.reverse(reversed);
        return reversed;
    }

    public void removeHistory(String query) {
        searchHistory.remove(query);
        org.json.JSONArray arr = new org.json.JSONArray();
        List<String> list = new ArrayList<>(searchHistory);
        for (int i = list.size() - 1; i >= 0; i--) arr.put(list.get(i));
        App.get().settingsStore.putString("etp_search_history", arr.toString());
    }

    public void clearHistory() {
        searchHistory.clear();
        App.get().settingsStore.putString("etp_search_history", "[]");
    }

    /** 修改过滤条件后，如果有当前查询则重新搜索。 */
    public void onFilterChanged() {
        if (!currentQuery.isEmpty()) {
            search(currentQuery);
        }
    }

    // ==================== 生命周期 ====================

    @Override
    protected void onCleared() {
        connecting = false;
        verified = false;
        pendingQuery = null;
        cancelActiveQuery(); // 页面销毁时中断仍在进行中的查询
        super.onCleared();
    }
}
