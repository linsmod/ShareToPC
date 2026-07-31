package github.linsmod.sharetopc.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
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
import github.linsmod.sharetopc.discovery.DiscoveredDevice;
import github.linsmod.sharetopc.transfer.EtpClient;

/**
 * ETP 文件搜索界面（v3 — 列表为主，连接收进 BottomSheet）。
 */
public class EtpSearchActivity extends androidx.appcompat.app.AppCompatActivity {

    private enum ConnState { DISCONNECTED, CONNECTING, CONNECTED, LOGGED_IN }

    private EtpClient client;
    private ConnState connState = ConnState.DISCONNECTED;
    private String connectedHost;
    private int connectedPort;

    // 工具栏设备芯片
    private Chip chipDevice;

    // 搜索
    private TextInputEditText etSearch;
    private ChipGroup cgHistory;
    private ProgressBar progressSearch;
    private RecyclerView rvResults;
    private View layoutEmpty;
    private TextView tvEmptyTitle;

    // 设备列表
    private List<DiscoveredDevice> savedDevices = new ArrayList<>();

    // BottomSheet
    private BottomSheetDialog deviceSheet;

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
        setContentView(R.layout.activity_etp_search);

        // Toolbar
        androidx.appcompat.widget.Toolbar toolbar = findViewById(R.id.toolbarEtpSearch);
        toolbar.setNavigationOnClickListener(v -> finish());

        // 设备芯片
        chipDevice = findViewById(R.id.chipDevice);
        chipDevice.setOnClickListener(v -> showDeviceSheet());

        // 筛选路径 FAB
        findViewById(R.id.fabPathPreset).setOnClickListener(v ->
                startActivity(new Intent(this, PathPresetActivity.class)));

        // 搜索栏
        etSearch = findViewById(R.id.etSearch);
        cgHistory = findViewById(R.id.cgRecentSearches);
        progressSearch = findViewById(R.id.progressSearch);
        rvResults = findViewById(R.id.rvResults);
        layoutEmpty = findViewById(R.id.layoutEmpty);
        tvEmptyTitle = findViewById(R.id.tvEmptyTitle);

        rvResults.setLayoutManager(new LinearLayoutManager(this));
        rvResults.addItemDecoration(new DividerItemDecoration(this, DividerItemDecoration.VERTICAL));
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
        loadDevices();
        applyDisconnectedUI();
        autoConnect();
    }

    // ==================== 设备 & 连接 ====================

    private void loadDevices() {
        savedDevices = App.get().deviceStore.loadAll();
    }

    private void autoConnect() {
        String lastHost = App.get().settingsStore.getLastEtpHost();
        int lastPort = App.get().settingsStore.getLastEtpPort();
        if (!lastHost.isEmpty() && lastPort > 0) {
            connectToDevice(lastHost, lastPort);
        }
    }

    private void connectToDevice(String host, int port) {
        EtpClient old = client;
        client = new EtpClient(host, port);

        setConnState(ConnState.CONNECTING);

        App.get().ioExecutor.execute(() -> {
            if (old != null) old.close();
            try {
                client.connect();
                App.get().settingsStore.setLastEtpHost(host);
                App.get().settingsStore.setLastEtpPort(port);

                // 自动匿名登录
                try {
                    if (client.login("anonymous", "")) {
                        connectedHost = host;
                        connectedPort = port;
                        handler.post(() -> setConnState(ConnState.LOGGED_IN));
                        return;
                    }
                } catch (IOException ignored) {}

                // 匿名失败，需要输入凭证
                connectedHost = host;
                connectedPort = port;
                handler.post(() -> {
                    setConnState(ConnState.CONNECTED);
                    showAuthInSheet(host);
                });

            } catch (IOException e) {
                handler.post(() -> {
                    setConnState(ConnState.DISCONNECTED);
                    Toast.makeText(this,
                            getString(R.string.etp_connect_failed, e.getMessage()),
                            Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void doLogin(String user, String pass) {
        if (client == null || !client.isConnected()) return;

        App.get().ioExecutor.execute(() -> {
            try {
                boolean ok = client.login(user, pass);
                handler.post(() -> {
                    if (ok) {
                        dismissDeviceSheet();
                        setConnState(ConnState.LOGGED_IN);
                        Toast.makeText(this,
                                getString(R.string.etp_login_ok, user),
                                Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this,
                                R.string.etp_login_failed, Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (IOException e) {
                handler.post(() -> Toast.makeText(this,
                        getString(R.string.etp_login_error, e.getMessage()),
                        Toast.LENGTH_SHORT).show());
            }
        });
    }

    // ==================== BottomSheet ====================

    private void showDeviceSheet() {
        deviceSheet = new BottomSheetDialog(this);
        View sheet = LayoutInflater.from(this)
                .inflate(R.layout.bottom_sheet_connect, null);
        deviceSheet.setContentView(sheet);

        // 状态文本
        TextView tvStatus = sheet.findViewById(R.id.tvSheetStatus);

        // 设备列表
        LinearLayout layoutDevices = sheet.findViewById(R.id.layoutDeviceList);
        populateDeviceList(layoutDevices);

        // 手动输入区域
        View tvManualToggle = sheet.findViewById(R.id.tvManualToggle);
        LinearLayout layoutManual = sheet.findViewById(R.id.layoutManualInput);
        TextInputEditText etHost = sheet.findViewById(R.id.etSheetHost);
        TextInputEditText etPort = sheet.findViewById(R.id.etSheetPort);
        Button btnConnect = sheet.findViewById(R.id.btnSheetConnect);
        LinearLayout layoutAuth = sheet.findViewById(R.id.layoutSheetAuth);
        TextInputEditText etUser = sheet.findViewById(R.id.etSheetUser);
        TextInputEditText etPass = sheet.findViewById(R.id.etSheetPass);
        Button btnLogin = sheet.findViewById(R.id.btnSheetLogin);

        // 预填上次的 host/port
        String lastHost = App.get().settingsStore.getLastEtpHost();
        int lastPort = App.get().settingsStore.getLastEtpPort();
        if (!lastHost.isEmpty()) etHost.setText(lastHost);
        if (lastPort > 0) etPort.setText(String.valueOf(lastPort));

        tvManualToggle.setOnClickListener(v -> {
            boolean show = layoutManual.getVisibility() != View.VISIBLE;
            layoutManual.setVisibility(show ? View.VISIBLE : View.GONE);
        });

        btnConnect.setOnClickListener(v -> {
            final String host = etHost.getText() != null ? etHost.getText().toString().trim() : "";
            final int port = parsePort(etPort.getText());
            if (host.isEmpty()) {
                Toast.makeText(this, R.string.etp_host_required, Toast.LENGTH_SHORT).show();
                return;
            }

            btnConnect.setEnabled(false);
            layoutAuth.setVisibility(View.GONE);
            tvStatus.setVisibility(View.VISIBLE);
            tvStatus.setText(R.string.etp_sheet_connecting_status);

            EtpClient oldCli = client;
            client = new EtpClient(host, port);

            App.get().ioExecutor.execute(() -> {
                if (oldCli != null) oldCli.close();
                try {
                    client.connect();
                    App.get().settingsStore.setLastEtpHost(host);
                    App.get().settingsStore.setLastEtpPort(port);

                    try {
                        if (client.login("anonymous", "")) {
                            connectedHost = host;
                            connectedPort = port;
                            handler.post(() -> {
                                dismissDeviceSheet();
                                setConnState(ConnState.LOGGED_IN);
                            });
                            return;
                        }
                    } catch (IOException ignored) {}

                    connectedHost = host;
                    connectedPort = port;
                    handler.post(() -> {
                        btnConnect.setEnabled(true);
                        tvStatus.setVisibility(View.GONE);
                        layoutAuth.setVisibility(View.VISIBLE);
                        setConnState(ConnState.CONNECTED);
                    });
                } catch (IOException e) {
                    handler.post(() -> {
                        btnConnect.setEnabled(true);
                        tvStatus.setVisibility(View.GONE);
                        Toast.makeText(this,
                                getString(R.string.etp_connect_failed, e.getMessage()),
                                Toast.LENGTH_SHORT).show();
                    });
                }
            });
        });

        btnLogin.setOnClickListener(v -> {
            String user = etUser.getText() != null ? etUser.getText().toString().trim() : "";
            final String u = user.isEmpty() ? "anonymous" : user;
            final String p = etPass.getText() != null ? etPass.getText().toString() : "";

            btnLogin.setEnabled(false);
            doLogin(u, p);
            handler.postDelayed(() -> btnLogin.setEnabled(true), 2000);
        });

        // 端口回车 → 连接
        etPort.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                btnConnect.performClick();
                return true;
            }
            return false;
        });

        deviceSheet.show();
    }

    /** 连接成功但需要登录时，重新打开 Sheet 展示认证栏 */
    private void showAuthInSheet(String host) {
        dismissDeviceSheet();
        showDeviceSheet();

        // 延迟展开手动输入 + 认证栏
        handler.postDelayed(() -> {
            if (deviceSheet != null && deviceSheet.isShowing()) {
                View sheet = deviceSheet.findViewById(R.id.sheetRoot);
                if (sheet == null) return;
                sheet.findViewById(R.id.layoutManualInput).setVisibility(View.VISIBLE);
                sheet.findViewById(R.id.layoutSheetAuth).setVisibility(View.VISIBLE);

                TextInputEditText etHost = sheet.findViewById(R.id.etSheetHost);
                if (etHost != null && host != null) etHost.setText(host);
            }
        }, 200);
    }

    private void dismissDeviceSheet() {
        if (deviceSheet != null && deviceSheet.isShowing()) {
            deviceSheet.dismiss();
        }
        deviceSheet = null;
    }

    private static int parsePort(@Nullable CharSequence text) {
        try {
            return Integer.parseInt(text != null ? text.toString().trim() : "21");
        } catch (NumberFormatException e) {
            return 21;
        }
    }

    private void populateDeviceList(LinearLayout container) {
        container.removeAllViews();

        if (savedDevices.isEmpty()) {
            TextView tv = new TextView(this);
            tv.setText(R.string.etp_sheet_no_devices);
            tv.setTextSize(13);
            tv.setTextColor(getThemeColor(android.R.attr.textColorTertiary));
            tv.setPadding(0, 8, 0, 8);
            container.addView(tv);
            return;
        }

        int padding = dp(12);
        for (int i = 0; i < savedDevices.size(); i++) {
            DiscoveredDevice d = savedDevices.get(i);

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(padding, padding, padding, padding);
            row.setClickable(true);
            row.setFocusable(true);
            TypedValue typedValue = new TypedValue();
            getTheme().resolveAttribute(android.R.attr.selectableItemBackground, typedValue, true);
            row.setForeground(getDrawable(typedValue.resourceId));
            row.setBackgroundResource(i == 0 ? android.R.color.transparent : android.R.color.transparent);

            // 分隔线（除第一个外）
            if (i > 0) {
                View divider = new View(this);
                divider.setLayoutParams(new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 1));
                divider.setBackgroundColor(getThemeColor(android.R.attr.listDivider));
                container.addView(divider);
            }

            TextView tvName = new TextView(this);
            tvName.setText(d.displayName());
            tvName.setTextSize(15);
            tvName.setTextColor(getThemeColor(android.R.attr.textColorPrimary));
            row.addView(tvName);

            TextView tvHost = new TextView(this);
            tvHost.setText(d.host);
            tvHost.setTextSize(12);
            tvHost.setTextColor(getThemeColor(android.R.attr.textColorSecondary));
            row.addView(tvHost);

            final String host = d.host;
            row.setOnClickListener(v -> {
                dismissDeviceSheet();
                connectToDevice(host, 21);
            });

            container.addView(row);
        }
    }

    private int dp(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }

    /** 解析主题属性颜色，兼容亮色/暗色模式。 */
    private int getThemeColor(int attr) {
        TypedValue tv = new TypedValue();
        getTheme().resolveAttribute(attr, tv, true);
        return getColor(tv.resourceId);
    }

    // ==================== 连接状态 UI ====================

    private void setConnState(ConnState state) {
        connState = state;
        switch (state) {
            case CONNECTING:
                chipDevice.setText(R.string.etp_connecting);
                chipDevice.setChipIconResource(android.R.drawable.presence_away);
                chipDevice.setChipIconTintResource(android.R.color.holo_orange_dark);
                break;
            case CONNECTED:
                chipDevice.setText(connectedHost + ":" + connectedPort);
                // 需要登录时用橙色表示警告
                chipDevice.setChipIconResource(android.R.drawable.ic_lock_idle_lock);
                chipDevice.setChipIconTintResource(android.R.color.holo_orange_dark);
                break;
            case LOGGED_IN:
                chipDevice.setText(connectedHost + ":" + connectedPort);
                chipDevice.setChipIconResource(android.R.drawable.presence_online);
                chipDevice.setChipIconTintResource(android.R.color.holo_green_dark);
                break;
            case DISCONNECTED:
                chipDevice.setText(R.string.etp_select_device);
                chipDevice.setChipIconResource(android.R.drawable.presence_offline);
                chipDevice.setChipIconTintResource(android.R.color.darker_gray);
                break;
        }
    }

    private void applyDisconnectedUI() {
        setConnState(ConnState.DISCONNECTED);
    }

    // ==================== 搜索 ====================

    private void doSearch() {
        String query = etSearch.getText() != null ? etSearch.getText().toString().trim() : "";
        if (query.isEmpty()) return;

        addSearchHistory(query);

        if (connState == ConnState.LOGGED_IN) {
            executeSearch(query);
            return;
        }

        // 自动连接 → 登录 → 搜索
        progressSearch.setVisibility(View.VISIBLE);

        if (connState == ConnState.CONNECTED) {
            tryAutoLoginAndSearch(query);
        } else {
            String lastHost = App.get().settingsStore.getLastEtpHost();
            int lastPort = App.get().settingsStore.getLastEtpPort();
            if (!lastHost.isEmpty() && lastPort > 0) {
                connectAndLoginAndSearch(lastHost, lastPort, query);
            } else if (!savedDevices.isEmpty()) {
                DiscoveredDevice d = savedDevices.get(0);
                connectAndLoginAndSearch(d.host, 21, query);
            } else {
                progressSearch.setVisibility(View.GONE);
                Toast.makeText(this, "请先选择设备", Toast.LENGTH_SHORT).show();
                showDeviceSheet();
            }
        }
    }

    /** 已连接但未登录 → 自动匿名登录后搜索 */
    private void tryAutoLoginAndSearch(String query) {
        App.get().ioExecutor.execute(() -> {
            try {
                if (client.login("anonymous", "")) {
                    handler.post(() -> {
                        setConnState(ConnState.LOGGED_IN);
                        executeSearch(query);
                    });
                } else {
                    handler.post(() -> {
                        progressSearch.setVisibility(View.GONE);
                        showAuthInSheet(connectedHost);
                    });
                }
            } catch (IOException e) {
                handler.post(() -> {
                    progressSearch.setVisibility(View.GONE);
                    setConnState(ConnState.DISCONNECTED);
                    Toast.makeText(this, "登录失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    /** 连接 → 自动登录 → 搜索 */
    private void connectAndLoginAndSearch(String host, int port, String query) {
        EtpClient old = client;
        client = new EtpClient(host, port);
        setConnState(ConnState.CONNECTING);

        App.get().ioExecutor.execute(() -> {
            if (old != null) old.close();
            try {
                client.connect();
                App.get().settingsStore.setLastEtpHost(host);
                App.get().settingsStore.setLastEtpPort(port);

                if (client.login("anonymous", "")) {
                    connectedHost = host;
                    connectedPort = port;
                    handler.post(() -> {
                        setConnState(ConnState.LOGGED_IN);
                        executeSearch(query);
                    });
                } else {
                    connectedHost = host;
                    connectedPort = port;
                    handler.post(() -> {
                        progressSearch.setVisibility(View.GONE);
                        setConnState(ConnState.CONNECTED);
                        showAuthInSheet(host);
                    });
                }
            } catch (IOException e) {
                handler.post(() -> {
                    progressSearch.setVisibility(View.GONE);
                    setConnState(ConnState.DISCONNECTED);
                    Toast.makeText(this, "连接失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    /**
     * 构造 Everything path: 过滤器，将用户勾选的路径预设拼接到查询中。
     * 无任何勾选时返回原始 query。
     */
    private String buildPathFilter(String rawQuery) {
        String json = App.get().settingsStore.getString("etp_path_presets", null);
        PathPresetActivity.PathPresets presets;
        if (json != null) {
            try {
                presets = new Gson().fromJson(json, PathPresetActivity.PathPresets.class);
            } catch (Exception ignored) {
                presets = PathPresetActivity.PathPresets.defaults();
            }
        } else {
            presets = PathPresetActivity.PathPresets.defaults();
        }
        if (presets == null) return rawQuery;

        // builtin key → Everything path pattern（用 *\ 前缀匹配任意盘符+用户目录）
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
            // 含空格或特殊字符则加引号
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
        PathPresetActivity.PathPresets p;
        if (json != null) {
            try {
                p = new Gson().fromJson(json, PathPresetActivity.PathPresets.class);
            } catch (Exception e) {
                p = PathPresetActivity.PathPresets.defaults();
            }
        } else {
            p = PathPresetActivity.PathPresets.defaults();
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
                        layoutEmpty.setVisibility(View.GONE);
                        rvResults.setVisibility(View.VISIBLE);
                    }
                });
            } catch (IOException e) {
                handler.post(() -> {
                    progressSearch.setVisibility(View.GONE);
                    setConnState(ConnState.DISCONNECTED);
                    Toast.makeText(this,
                            getString(R.string.etp_search_error, e.getMessage()),
                            Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void showEmpty(String title) {
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

        TextView label = new TextView(this);
        label.setText("最近：");
        label.setTextSize(13);
        label.setTextColor(getThemeColor(android.R.attr.textColorSecondary));
        label.setGravity(android.view.Gravity.CENTER_VERTICAL);
        ChipGroup.LayoutParams lp = new ChipGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cgHistory.addView(label, lp);

        List<String> reversed = new ArrayList<>(searchHistory);
        java.util.Collections.reverse(reversed);
        for (String q : reversed) {
            Chip chip = new Chip(this);
            chip.setText(q);
            chip.setClickable(true);
            chip.setCheckable(false);
            chip.setOnClickListener(v -> {
                etSearch.setText(q);
                doSearch();
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

            final String copyPath = fullPath;
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

    /**
     * 显示文件属性对话框。
     */
    private void showFilePropertiesDialog(EtpClient.EtpSearchResult r) {
        // 构建属性信息
        StringBuilder body = new StringBuilder();

        // 完整路径
        body.append("路径：").append(r.fullPath()).append("\n\n");

        // 类型
        body.append("类型：").append(r.isFolder ? "文件夹" : "文件").append("\n\n");

        // 大小
        if (r.size >= 0) {
            body.append("大小：").append(EtpClient.EtpSearchResult.formatSize(r.size));
            if (r.size >= 1024) {
                body.append("（").append(String.format("%,d 字节", r.size)).append("）");
            }
            body.append("\n\n");
        }

        // 修改时间
        if (r.dateModified > 0) {
            long millis = EtpClient.EtpSearchResult.filetimeToMillis(r.dateModified);
            body.append("修改时间：").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                    .format(new Date(millis))).append("\n\n");
        }

        // 创建时间
        if (r.dateCreated > 0) {
            long millis = EtpClient.EtpSearchResult.filetimeToMillis(r.dateCreated);
            body.append("创建时间：").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                    .format(new Date(millis))).append("\n\n");
        }

        // 文件属性
        if (r.attributes >= 0) {
            body.append("属性：").append(formatFileAttributes(r.attributes));
        }

        TextView tv = new TextView(this);
        tv.setText(body.toString());
        tv.setTextSize(14);
        tv.setTextColor(getThemeColor(android.R.attr.textColorPrimary));
        tv.setPadding(dp(16), dp(16), dp(16), dp(8));
        tv.setTextIsSelectable(true);

        new AlertDialog.Builder(this)
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

    /**
     * 格式化 Windows 文件属性标志位。
     */
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

    // ==================== 生命周期 ====================

    @Override
    protected void onDestroy() {
        super.onDestroy();
        dismissDeviceSheet();
        EtpClient toClose = client;
        client = null;
        if (toClose != null) {
            App.get().ioExecutor.execute(() -> {
                try { toClose.close(); } catch (Exception ignored) {}
            });
        }
    }
}
