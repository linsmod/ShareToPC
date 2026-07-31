package github.linsmod.sharetopc.ui;

import android.os.Handler;
import android.os.Looper;

import androidx.lifecycle.ViewModel;

import com.google.gson.Gson;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import github.linsmod.sharetopc.App;
import github.linsmod.sharetopc.transfer.EtpClient;

/**
 * ETP 搜索 ViewModel — 用 Consumer 回调代替 LiveData。
 * 管理连接、搜索、分页、历史，Activity 只负责渲染状态。
 */
public class EtpSearchViewModel extends ViewModel {

    private static final int PAGE_SIZE = 50;
    private static final int MAX_HISTORY = 10;

    private EtpClient client;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // 观察者
    private Consumer<SearchUiState> stateObserver;

    // 搜索上下文
    private String currentQuery = "";
    private int currentOffset = 0;
    private boolean hasMore = false;
    private int resultCount = 0;

    // 累积结果（分页追加）
    private final List<EtpClient.EtpSearchResult> allResults = new ArrayList<>();

    // 搜索历史（LinkedHashSet 做去重 + 保序）
    private final Set<String> searchHistory = new LinkedHashSet<>();

    // ==================== 状态回调 ====================

    /** 注册状态观察者，注册时立即回调当前状态。 */
    public void observe(Consumer<SearchUiState> observer) {
        this.stateObserver = observer;
        if (client != null && client.isLoggedIn()) {
            emit(new SearchUiState.Ready());
        } else {
            emit(new SearchUiState.Connecting());
        }
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

    public void connect(String host, int port) {
        emit(new SearchUiState.Connecting());
        client = new EtpClient(host, port);

        App.get().ioExecutor.execute(() -> {
            try {
                client.connect();
                if (client.login("anonymous", "")) {
                    // 尝试 app 用户名
                }
                // 匿名登录失败，尝试空密码
                if (!client.isLoggedIn()) {
                    try { client.login("anonymous", ""); } catch (IOException ignored) {}
                }
                if (client.isLoggedIn()) {
                    App.get().settingsStore.setLastEtpHost(host);
                    App.get().settingsStore.setLastEtpPort(port);
                    emit(new SearchUiState.Ready());
                } else {
                    emit(new SearchUiState.Error("登录失败，请检查 ETP 服务是否开启"));
                }
            } catch (IOException e) {
                emit(new SearchUiState.Error("连接失败: " + e.getMessage()));
            }
        });
    }

    public boolean isConnected() {
        return client != null && client.isConnected() && client.isLoggedIn();
    }

    // ==================== 搜索 ====================

    /** 执行新搜索（重置分页）。 */
    public void search(String query) {
        if (query.isEmpty()) return;
        currentQuery = query;
        currentOffset = 0;
        allResults.clear();
        addSearchHistory(query);
        emit(new SearchUiState.Searching(query));
        doQuery(query, 0);
    }

    /** 加载更多（翻页）。 */
    public void loadMore() {
        if (!hasMore || currentQuery.isEmpty()) return;
        currentOffset += PAGE_SIZE;
        doQuery(currentQuery, currentOffset);
    }

    private void doQuery(String query, int offset) {
        if (!isConnected()) {
            emit(new SearchUiState.Error("未连接"));
            return;
        }

        int columns = EtpClient.SIZE_COLUMN | EtpClient.PATH_COLUMN
                | EtpClient.DATE_MODIFIED_COLUMN | EtpClient.DATE_CREATED_COLUMN
                | EtpClient.ATTRIBUTES_COLUMN;

        String filteredQuery = buildPathFilter(query);
        QueryParams qp = loadQueryParams();
        EtpClient c = client;

        App.get().ioExecutor.execute(() -> {
            try {
                List<EtpClient.EtpSearchResult> page = c.query(filteredQuery, columns,
                        qp.matchCase, qp.matchPath, qp.matchRegex, qp.matchWholeWord,
                        qp.sortName, qp.sortAsc, offset, PAGE_SIZE);
                mainHandler.post(() -> onQueryResult(query, page));
            } catch (IOException e) {
                mainHandler.post(() -> emit(new SearchUiState.Error("搜索出错: " + e.getMessage())));
            }
        });
    }

    private void onQueryResult(String query, List<EtpClient.EtpSearchResult> page) {
        if (!query.equals(currentQuery)) return; // 过期结果，忽略

        allResults.addAll(page);
        hasMore = page.size() >= PAGE_SIZE;
        resultCount = allResults.size();

        if (allResults.isEmpty()) {
            emit(new SearchUiState.Empty(query));
        } else {
            emit(new SearchUiState.HasResults(
                    new ArrayList<>(allResults), resultCount, hasMore, query));
        }
    }

    // ==================== 路径过滤 ====================

    /** 构造 Everything path: 过滤器。 */
    private String buildPathFilter(String rawQuery) {
        String json = App.get().settingsStore.getString("etp_path_presets", null);
        EtpSettingsActivity.PathPresets presets;
        if (json != null) {
            try {
                presets = new Gson().fromJson(json, EtpSettingsActivity.PathPresets.class);
            } catch (Exception e) {
                presets = EtpSettingsActivity.PathPresets.defaults();
            }
        } else {
            presets = EtpSettingsActivity.PathPresets.defaults();
        }
        if (presets == null) return rawQuery;

        Map<String, String> builtinMap = new LinkedHashMap<>();
        builtinMap.put("desktop", "*\\Desktop\\");
        builtinMap.put("documents", "*\\Documents\\");
        builtinMap.put("downloads", "*\\Downloads\\");
        builtinMap.put("videos", "*\\Videos\\");
        builtinMap.put("pictures", "*\\Pictures\\");
        builtinMap.put("music", "*\\Music\\");

        List<String> paths = new ArrayList<>();
        for (Map.Entry<String, Boolean> e : presets.builtin.entrySet()) {
            if (Boolean.TRUE.equals(e.getValue()) && builtinMap.containsKey(e.getKey())) {
                paths.add(builtinMap.get(e.getKey()));
            }
        }
        for (Map.Entry<String, Boolean> e : presets.drives.entrySet()) {
            if (Boolean.TRUE.equals(e.getValue())) paths.add(e.getKey() + "\\");
        }
        for (String p : presets.custom) {
            String trimmed = p.trim();
            if (!trimmed.isEmpty()) paths.add(trimmed);
        }
        if (paths.isEmpty()) return rawQuery;

        StringBuilder sb = new StringBuilder(rawQuery);
        sb.append(" path:");
        for (int i = 0; i < paths.size(); i++) {
            if (i > 0) sb.append("|");
            String path = paths.get(i);
            if (path.contains(" ") || path.contains("(") || path.contains(")") || path.contains("&")) {
                sb.append("\"").append(path).append("\"");
            } else {
                sb.append(path);
            }
        }
        return sb.toString();
    }

    /** 从预设中解析查询参数。 */
    private QueryParams loadQueryParams() {
        String json = App.get().settingsStore.getString("etp_path_presets", null);
        EtpSettingsActivity.PathPresets p;
        if (json != null) {
            try {
                p = new Gson().fromJson(json, EtpSettingsActivity.PathPresets.class);
            } catch (Exception e) {
                p = EtpSettingsActivity.PathPresets.defaults();
            }
        } else {
            p = EtpSettingsActivity.PathPresets.defaults();
        }
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

    private void addSearchHistory(String query) {
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

    /** 修改过滤条件后，如果有当前查询则重新搜索。 */
    public void onFilterChanged() {
        if (!currentQuery.isEmpty()) {
            search(currentQuery);
        }
    }

    // ==================== 生命周期 ====================

    @Override
    protected void onCleared() {
        disconnect();
        super.onCleared();
    }

    public void disconnect() {
        EtpClient c = client;
        client = null;
        if (c != null) {
            App.get().ioExecutor.execute(() -> {
                try { c.close(); } catch (Exception ignored) {}
            });
        }
    }
}
