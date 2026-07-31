package github.linsmod.sharetopc.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.os.Bundle;

import androidx.activity.EdgeToEdge;
import androidx.annotation.Nullable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.divider.MaterialDividerItemDecoration;
import com.google.android.material.textfield.TextInputEditText;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.google.gson.Gson;

import github.linsmod.sharetopc.App;
import github.linsmod.sharetopc.R;
import github.linsmod.sharetopc.transfer.EtpClient;

/**
 * ETP 远程文件搜索界面（精简版 — 从设备页入口，无连接信息/设备管理）。
 */
public class EtpSearchActivity extends androidx.appcompat.app.AppCompatActivity {

    public static final String EXTRA_DEVICE_NAME = "device_name";
    public static final String EXTRA_HOST = "host";

    private EtpClient client;
    private boolean loggedIn;
    private String deviceHost;
    private int etpPort;

    // 搜索
    private TextInputEditText etSearch;
    private ChipGroup cgHistory;
    private ProgressBar progressSearch;
    private RecyclerView rvResults;
    private View layoutEmpty;
    private TextView tvEmptyTitle;
    private TextView tvEmptyHint;
    private TextView tvResultCount;

    private final ResultAdapter resultAdapter = new ResultAdapter();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private String debounceQuery = "";
    private final Runnable debounceSearch = new Runnable() {
        @Override
        public void run() {
            doSearch();
        }
    };
    private final Set<String> searchHistory = new LinkedHashSet<>();

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_etp_search);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.etpRoot), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        // 读取设备信息
        Intent intent = getIntent();
        String deviceName = intent.getStringExtra(EXTRA_DEVICE_NAME);
        deviceHost = intent.getStringExtra(EXTRA_HOST);
        etpPort = App.get().settingsStore.getEtpPort();
        if (deviceHost == null || deviceHost.isEmpty()) {
            Toast.makeText(this, "未指定设备", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        // Toolbar 标题
        androidx.appcompat.widget.Toolbar toolbar = findViewById(R.id.toolbarEtpSearch);
        toolbar.setTitle(getString(R.string.etp_search_title_format,
                deviceName != null ? deviceName : deviceHost));
        toolbar.setNavigationOnClickListener(v -> finish());

        // 筛选路径 FAB
        findViewById(R.id.fabPathPreset).setOnClickListener(v ->
                startActivity(new Intent(this, EtpSettingsActivity.class)));

        // 搜索栏
        etSearch = findViewById(R.id.etSearch);
        cgHistory = findViewById(R.id.cgRecentSearches);
        progressSearch = findViewById(R.id.progressSearch);
        rvResults = findViewById(R.id.rvResults);
        layoutEmpty = findViewById(R.id.layoutEmpty);
        tvEmptyTitle = findViewById(R.id.tvEmptyTitle);
        tvEmptyHint = findViewById(R.id.tvEmptyHint);
        tvResultCount = findViewById(R.id.tvResultCount);

        rvResults.setLayoutManager(new LinearLayoutManager(this));
        rvResults.addItemDecoration(new MaterialDividerItemDecoration(this,
                MaterialDividerItemDecoration.VERTICAL));
        rvResults.setAdapter(resultAdapter);

        // 输入防抖搜索（300ms）
        etSearch.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(android.text.Editable s) {
                handler.removeCallbacks(debounceSearch);
                String query = s.toString().trim();
                if (query.isEmpty()) {
                    resultAdapter.setResults(new ArrayList<>());
                    showEmpty(getString(R.string.etp_empty_title));
                    return;
                }
                debounceQuery = query;
                handler.postDelayed(debounceSearch, 300);
            }
        });

        // 初始化
        loadSearchHistory();
        showEmpty(getString(R.string.etp_empty_title));
        connectDevice();
    }

    // ==================== 连接 ====================

    private void connectDevice() {
        client = new EtpClient(deviceHost, etpPort);

        App.get().ioExecutor.execute(() -> {
            try {
                client.connect();
                // 尝试匿名登录
                try {
                    if (client.login("anonymous", "")) {
                        loggedIn = true;
                        App.get().settingsStore.setLastEtpHost(deviceHost);
                        App.get().settingsStore.setLastEtpPort(etpPort);
                        return;
                    }
                } catch (IOException ignored) {}
                // 匿名失败，尝试无密码登录
                try {
                    if (client.login("anonymous", "")) {
                        loggedIn = true;
                        return;
                    }
                } catch (IOException ignored2) {}
                handler.post(() -> {
                    Toast.makeText(this,
                            getString(R.string.etp_login_failed),
                            Toast.LENGTH_SHORT).show();
                });
            } catch (IOException e) {
                handler.post(() -> {
                    Toast.makeText(this,
                            getString(R.string.etp_connect_failed, e.getMessage()),
                            Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    // ==================== 搜索 ====================

    private void doSearch() {
        String query = etSearch.getText() != null ? etSearch.getText().toString().trim() : "";
        if (query.isEmpty()) return;

        addSearchHistory(query);

        if (loggedIn) {
            executeSearch(query);
            return;
        }

        // 未登录 → 重连
        progressSearch.setVisibility(View.VISIBLE);
        App.get().ioExecutor.execute(() -> {
            try {
                if (client == null || !client.isConnected()) {
                    client = new EtpClient(deviceHost, etpPort);
                    client.connect();
                }
                if (client.login("anonymous", "")) {
                    loggedIn = true;
                    App.get().settingsStore.setLastEtpHost(deviceHost);
                    App.get().settingsStore.setLastEtpPort(etpPort);
                    handler.post(() -> executeSearch(query));
                } else {
                    handler.post(() -> {
                        progressSearch.setVisibility(View.GONE);
                        Toast.makeText(this, R.string.etp_login_failed, Toast.LENGTH_SHORT).show();
                    });
                }
            } catch (IOException e) {
                handler.post(() -> {
                    progressSearch.setVisibility(View.GONE);
                    Toast.makeText(this,
                            getString(R.string.etp_connect_failed, e.getMessage()),
                            Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    /**
     * 构造 Everything path: 过滤器，将用户勾选的路径预设拼接到查询中。
     */
    private String buildPathFilter(String rawQuery) {
        String json = App.get().settingsStore.getString("etp_path_presets", null);
        EtpSettingsActivity.PathPresets presets;
        if (json != null) {
            try {
                presets = new Gson().fromJson(json, EtpSettingsActivity.PathPresets.class);
            } catch (Exception ignored) {
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
            if (Boolean.TRUE.equals(e.getValue())) {
                paths.add(e.getKey() + "\\");
            }
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

    /** 从预设中解析查询参数 */
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

    /** 已登录状态下执行实际搜索 */
    private void executeSearch(String query) {
        int columns = EtpClient.SIZE_COLUMN | EtpClient.PATH_COLUMN
                | EtpClient.DATE_MODIFIED_COLUMN | EtpClient.DATE_CREATED_COLUMN
                | EtpClient.ATTRIBUTES_COLUMN;

        progressSearch.setVisibility(View.VISIBLE);

        String filteredQuery = buildPathFilter(query);
        QueryParams qp = loadQueryParams();

        App.get().ioExecutor.execute(() -> {
            try {
                List<EtpClient.EtpSearchResult> results = client.query(filteredQuery, columns,
                        qp.matchCase, qp.matchPath, qp.matchRegex, qp.matchWholeWord,
                        qp.sortName, qp.sortAsc,
                        0 /* offset */, qp.maxResults);
                handler.post(() -> {
                    progressSearch.setVisibility(View.GONE);
                    resultAdapter.setResults(results);
                    if (results.isEmpty()) {
                        showEmpty(getString(R.string.etp_no_results));
                    } else {
                        tvResultCount.setText(getString(R.string.etp_result_count, results.size()));
                        tvResultCount.setVisibility(View.VISIBLE);
                        layoutEmpty.setVisibility(View.GONE);
                        rvResults.setVisibility(View.VISIBLE);
                    }
                });
            } catch (IOException e) {
                handler.post(() -> {
                    progressSearch.setVisibility(View.GONE);
                    loggedIn = false;
                    Toast.makeText(this,
                            getString(R.string.etp_search_error, e.getMessage()),
                            Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void showEmpty(String title) {
        tvResultCount.setVisibility(View.GONE);
        layoutEmpty.setVisibility(View.VISIBLE);
        rvResults.setVisibility(View.GONE);
        tvEmptyTitle.setText(title);
    }

    // ==================== 搜索历史 ====================

    private void loadSearchHistory() {
        String json = App.get().settingsStore.getString("etp_search_history", "");
        searchHistory.clear();
        if (!json.isEmpty()) {
            try {
                org.json.JSONArray arr = new org.json.JSONArray(json);
                for (int i = 0; i < arr.length(); i++) {
                    searchHistory.add(arr.getString(i));
                }
            } catch (org.json.JSONException ignored) {}
        }
        refreshHistoryChips();
    }

    private void addSearchHistory(String query) {
        searchHistory.remove(query);
        searchHistory.add(query);

        List<String> trimmed = new ArrayList<>(searchHistory);
        if (trimmed.size() > 10) {
            searchHistory.clear();
            for (int i = trimmed.size() - 10; i < trimmed.size(); i++) {
                searchHistory.add(trimmed.get(i));
            }
        }

        org.json.JSONArray arr = new org.json.JSONArray();
        List<String> list = new ArrayList<>(searchHistory);
        for (int i = list.size() - 1; i >= 0; i--) {
            arr.put(list.get(i));
        }
        App.get().settingsStore.putString("etp_search_history", arr.toString());

        refreshHistoryChips();
    }

    private void refreshHistoryChips() {
        cgHistory.removeAllViews();
        if (searchHistory.isEmpty()) {
            cgHistory.setVisibility(View.GONE);
            return;
        }
        cgHistory.setVisibility(View.VISIBLE);

        List<String> reversed = new ArrayList<>(searchHistory);
        java.util.Collections.reverse(reversed);
        for (int i = 0; i < reversed.size(); i++) {
            String q = reversed.get(i);
            Chip chip = new Chip(this, null, com.google.android.material.R.attr.chipStyle);
            chip.setText(q);
            chip.setClickable(true);
            chip.setCheckable(false);
            chip.setChipIconResource(android.R.drawable.ic_menu_recent_history);
            chip.setCloseIconVisible(true);
            final int idx = i;
            chip.setOnClickListener(v -> {
                etSearch.setText(q);
                doSearch();
            });
            chip.setOnCloseIconClickListener(v -> {
                // 从历史中移除该项
                List<String> list = new ArrayList<>(searchHistory);
                list.remove(q);
                searchHistory.clear();
                searchHistory.addAll(list);
                org.json.JSONArray arr = new org.json.JSONArray();
                for (int j = list.size() - 1; j >= 0; j--) {
                    arr.put(list.get(j));
                }
                App.get().settingsStore.putString("etp_search_history", arr.toString());
                refreshHistoryChips();
            });
            cgHistory.addView(chip);
        }
    }

    // ==================== 结果列表 ====================

    private class ResultAdapter extends RecyclerView.Adapter<ResultAdapter.VH> {

        private List<EtpClient.EtpSearchResult> items = new ArrayList<>();

        void setResults(List<EtpClient.EtpSearchResult> results) {
            this.items = results != null ? results : new ArrayList<>();
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_etp_result, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            EtpClient.EtpSearchResult r = items.get(position);
            holder.tvName.setText(r.name);
            holder.tvIcon.setText(r.isFolder ? "📁" : "📄");

            StringBuilder info = new StringBuilder();
            if (r.size >= 0) {
                info.append(EtpClient.EtpSearchResult.formatSize(r.size));
            }
            if (r.dateModified > 0) {
                if (info.length() > 0) info.append("  |  ");
                long millis = EtpClient.EtpSearchResult.filetimeToMillis(r.dateModified);
                info.append(new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                        .format(new Date(millis)));
            }
            holder.tvInfo.setText(info.toString());

            String fullPath;
            if (r.path != null && !r.path.isEmpty()) {
                holder.tvPath.setVisibility(View.VISIBLE);
                fullPath = r.path + "\\" + r.name;
                holder.tvPath.setText(fullPath);
            } else {
                holder.tvPath.setVisibility(View.GONE);
                fullPath = r.name;
            }

            final EtpClient.EtpSearchResult resultRef = r;
            holder.itemView.setOnClickListener(v -> showFilePropertiesDialog(resultRef));
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        class VH extends RecyclerView.ViewHolder {
            final TextView tvIcon, tvName, tvInfo, tvPath;

            VH(@NonNull View itemView) {
                super(itemView);
                tvIcon = itemView.findViewById(R.id.tvIcon);
                tvName = itemView.findViewById(R.id.tvName);
                tvInfo = itemView.findViewById(R.id.tvInfo);
                tvPath = itemView.findViewById(R.id.tvPath);
            }
        }
    }

    /** 显示文件属性对话框。 */
    private void showFilePropertiesDialog(EtpClient.EtpSearchResult r) {
        StringBuilder body = new StringBuilder();
        body.append("路径：").append(r.fullPath()).append("\n\n");
        body.append("类型：").append(r.isFolder ? "文件夹" : "文件").append("\n\n");

        if (r.size >= 0) {
            body.append("大小：").append(EtpClient.EtpSearchResult.formatSize(r.size));
            if (r.size >= 1024) {
                body.append("（").append(String.format("%,d 字节", r.size)).append("）");
            }
            body.append("\n\n");
        }

        if (r.dateModified > 0) {
            long millis = EtpClient.EtpSearchResult.filetimeToMillis(r.dateModified);
            body.append("修改时间：").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                    .format(new Date(millis))).append("\n\n");
        }

        if (r.dateCreated > 0) {
            long millis = EtpClient.EtpSearchResult.filetimeToMillis(r.dateCreated);
            body.append("创建时间：").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                    .format(new Date(millis))).append("\n\n");
        }

        if (r.attributes >= 0) {
            body.append("属性：").append(formatFileAttributes(r.attributes));
        }

        TextView tv = new TextView(this);
        tv.setText(body.toString());
        tv.setTextSize(14);
        tv.setTextColor(getThemeColor(android.R.attr.textColorPrimary));
        tv.setPadding(dp(16), dp(16), dp(16), dp(8));
        tv.setTextIsSelectable(true);

        new MaterialAlertDialogBuilder(this)
                .setTitle(r.name)
                .setIcon(r.isFolder ? android.R.drawable.ic_dialog_info
                        : android.R.drawable.ic_dialog_info)
                .setView(tv)
                .setPositiveButton("复制路径", (d, w) -> {
                    ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("file_path", r.fullPath()));
                    Toast.makeText(this, getString(R.string.etp_copied, r.fullPath()),
                            Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private static String formatFileAttributes(int attrs) {
        StringBuilder sb = new StringBuilder();
        if ((attrs & 0x01) != 0) sb.append("只读 ");
        if ((attrs & 0x02) != 0) sb.append("隐藏 ");
        if ((attrs & 0x04) != 0) sb.append("系统 ");
        if ((attrs & 0x10) != 0) sb.append("目录 ");
        if ((attrs & 0x20) != 0) sb.append("存档 ");
        if ((attrs & 0x80) != 0) sb.append("普通 ");
        if ((attrs & 0x100) != 0) sb.append("临时 ");
        if ((attrs & 0x200) != 0) sb.append("稀疏 ");
        if ((attrs & 0x400) != 0) sb.append("重解析点 ");
        if ((attrs & 0x800) != 0) sb.append("压缩 ");
        if ((attrs & 0x1000) != 0) sb.append("脱机 ");
        if ((attrs & 0x2000) != 0) sb.append("未索引 ");
        if ((attrs & 0x4000) != 0) sb.append("加密 ");
        if (sb.length() == 0) sb.append("无");
        return sb.toString().trim();
    }

    private int dp(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }

    private int getThemeColor(int attr) {
        TypedValue tv = new TypedValue();
        getTheme().resolveAttribute(attr, tv, true);
        return getColor(tv.resourceId);
    }

    // ==================== 生命周期 ====================

    @Override
    protected void onDestroy() {
        super.onDestroy();
        EtpClient toClose = client;
        client = null;
        if (toClose != null) {
            App.get().ioExecutor.execute(() -> {
                try { toClose.close(); } catch (Exception ignored) {}
            });
        }
    }
}
