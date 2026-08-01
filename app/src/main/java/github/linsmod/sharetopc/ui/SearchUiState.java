package github.linsmod.sharetopc.ui;

import java.util.List;

import github.linsmod.sharetopc.transfer.EtpClient;

/**
 * ETP 搜索 UI 状态 — 用抽象类模拟 sealed class。
 * View 层只需 switch/instanceof 分发，不再手工切换 visibility。
 */
public abstract class SearchUiState {
    private SearchUiState() {}

    /** 连接中 */
    public static final class Connecting extends SearchUiState {
        @Override public String toString() { return "Connecting"; }
    }

    /** 等待搜索（已连接，无结果） */
    public static final class Ready extends SearchUiState {
        @Override public String toString() { return "Ready"; }
    }

    /** 搜索中 */
    public static final class Searching extends SearchUiState {
        public final String query;
        public Searching(String query) { this.query = query; }
        @Override public String toString() { return "Searching{" + query + "}"; }
    }

    /** 有结果 */
    public static final class HasResults extends SearchUiState {
        public final List<EtpClient.EtpSearchResult> items;
        public final long totalCount;
        public final boolean hasMore; // 真实匹配总数超出本次传输条数（受限）
        public final String query;
        public HasResults(List<EtpClient.EtpSearchResult> items, long totalCount,
                          boolean hasMore, String query) {
            this.items = items;
            this.totalCount = totalCount;
            this.hasMore = hasMore;
            this.query = query;
        }
        @Override public String toString() {
            return "HasResults{count=" + items.size() + ", total=" + totalCount + "}";
        }
    }

    /** 空结果 */
    public static final class Empty extends SearchUiState {
        public final String query;
        public final List<String> queryPaths;
        public Empty(String query, List<String> queryPaths) {
            this.query = query;
            this.queryPaths = queryPaths;
        }
        @Override public String toString() { return "Empty{" + query + ", paths=" + queryPaths + "}"; }
    }

    /** 加载更多状态：不改变结果页，只更新列表底部 footer（加载中/失败重试）。 */
    public static final class LoadMoreState extends SearchUiState {
        public final boolean loading;
        public final boolean error;
        public LoadMoreState(boolean loading, boolean error) {
            this.loading = loading;
            this.error = error;
        }
        @Override public String toString() {
            return "LoadMore{loading=" + loading + ", error=" + error + "}";
        }
    }

    /** 错误 */
    public static final class Error extends SearchUiState {
        public final String message;
        public Error(String message) { this.message = message; }
        @Override public String toString() { return "Error{" + message + "}"; }
    }
}
