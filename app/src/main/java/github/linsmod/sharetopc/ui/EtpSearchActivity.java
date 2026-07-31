package github.linsmod.sharetopc.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.textfield.TextInputEditText;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import github.linsmod.sharetopc.App;
import github.linsmod.sharetopc.R;
import github.linsmod.sharetopc.discovery.DiscoveredDevice;
import github.linsmod.sharetopc.transfer.EtpClient;

/**
 * ETP 文件搜索界面（v2 产品设计）。
 * <p>
 * 核心理念：打开即搜。连接是手段，搜索是目的。
 * <ul>
 *   <li>进入页面自动尝试连接上次的设备</li>
 *   <li>设备选择器一键切换，手动输入作为兜底</li>
 *   <li>搜索框始终可见，最近搜索一键复用</li>
 *   <li>点击结果项复制路径</li>
 * </ul>
 */
public class EtpSearchActivity extends AppCompatActivity {

    // 连接状态
    private enum ConnState { DISCONNECTED, CONNECTING, CONNECTED, LOGGED_IN }

    private EtpClient client;
    private ConnState connState = ConnState.DISCONNECTED;
    private String connectedHost;
    private int connectedPort;

    // 设备选择
    private Chip chipDevice;
    private TextView tvConnStatus;
    private List<DiscoveredDevice> savedDevices = new ArrayList<>();

    // 手动连接卡片
    private MaterialCardView cardManualConnect;
    private TextInputEditText etHost, etPort, etUser, etPass;
    private Button btnManualConnect, btnLogin;
    private View layoutAuth;

    // 搜索
    private TextInputEditText etSearch;
    private Button btnSearch;
    private ChipGroup cgHistory;
    private ProgressBar progressSearch;
    private RecyclerView rvResults;
    private View layoutEmpty;
    private TextView tvEmptyTitle, tvEmptyHint;

    private final ResultAdapter resultAdapter = new ResultAdapter();
    private final Handler handler = new Handler(Looper.getMainLooper());

    // 搜索历史（最多 10 条）
    private final Set<String> searchHistory = new LinkedHashSet<>();

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_etp_search);

        // Toolbar
        findViewById(R.id.toolbarEtpSearch).setOnClickListener(v -> finish());

        // ---- 设备选择条 ----
        chipDevice = findViewById(R.id.chipDevice);
        tvConnStatus = findViewById(R.id.tvConnectionStatus);
        chipDevice.setOnClickListener(v -> showDeviceMenu());

        // ---- 手动连接卡片 ----
        cardManualConnect = findViewById(R.id.cardManualConnect);
        etHost = findViewById(R.id.etHost);
        etPort = findViewById(R.id.etPort);
        btnManualConnect = findViewById(R.id.btnManualConnect);
        layoutAuth = findViewById(R.id.layoutAuth);
        etUser = findViewById(R.id.etUser);
        etPass = findViewById(R.id.etPass);
        btnLogin = findViewById(R.id.btnLogin);

        btnManualConnect.setOnClickListener(v -> doManualConnect());
        btnLogin.setOnClickListener(v -> doManualLogin());

        // 端口键盘回车 → 连接
        etPort.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                doManualConnect();
                return true;
            }
            return false;
        });
        etPass.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                doManualLogin();
                return true;
            }
            return false;
        });

        // ---- 搜索栏 ----
        etSearch = findViewById(R.id.etSearch);
        btnSearch = findViewById(R.id.btnSearch);
        cgHistory = findViewById(R.id.cgRecentSearches);
        progressSearch = findViewById(R.id.progressSearch);
        rvResults = findViewById(R.id.rvResults);
        layoutEmpty = findViewById(R.id.layoutEmpty);
        tvEmptyTitle = findViewById(R.id.tvEmptyTitle);
        tvEmptyHint = findViewById(R.id.tvEmptyHint);

        rvResults.setLayoutManager(new LinearLayoutManager(this));
        rvResults.addItemDecoration(new DividerItemDecoration(this, DividerItemDecoration.VERTICAL));
        rvResults.setAdapter(resultAdapter);

        btnSearch.setOnClickListener(v -> doSearch());
        etSearch.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                doSearch();
                return true;
            }
            return false;
        });

        // ---- 初始化 ----
        loadSearchHistory();
        loadDevices();
        applyDisconnectedState();
        autoConnect();
    }

    // ==================== 设备选择 ====================

    private void loadDevices() {
        savedDevices = App.get().deviceStore.loadAll();
    }

    private void showDeviceMenu() {
        PopupMenu popup = new PopupMenu(this, chipDevice);
        int idx = 0;

        // 已存储的设备
        for (DiscoveredDevice d : savedDevices) {
            String label = d.displayName() + "  (" + d.host + ")";
            popup.getMenu().add(0, idx, idx, label);
            idx++;
        }

        // 分隔线 + 手动输入
        if (idx > 0) {
            popup.getMenu().add(0, idx, idx, "──────────────").setEnabled(false);
            idx++;
        }
        popup.getMenu().add(0, idx, idx, R.string.etp_manual_input);
        final int manualIdx = idx;

        popup.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == manualIdx) {
                showManualConnect();
            } else if (id < savedDevices.size()) {
                DiscoveredDevice d = savedDevices.get(id);
                connectToDevice(d.host, 21);
            }
            return true;
        });
        popup.show();
    }

    private void connectToDevice(String host, int port) {
        // 隐藏手动卡片
        cardManualConnect.setVisibility(View.GONE);

        EtpClient old = client;
        client = new EtpClient(host, port);
        if (old != null) old.close();

        setConnState(ConnState.CONNECTING);
        updateDeviceChip(host, port);

        App.get().ioExecutor.execute(() -> {
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

                connectedHost = host;
                connectedPort = port;
                handler.post(() -> setConnState(ConnState.CONNECTED));

            } catch (IOException e) {
                handler.post(() -> {
                    setConnState(ConnState.DISCONNECTED);
                    updateDeviceChip(null, 0);
                    Toast.makeText(this, getString(R.string.etp_connect_failed, e.getMessage()),
                            Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void showManualConnect() {
        cardManualConnect.setVisibility(View.VISIBLE);
        layoutAuth.setVisibility(View.GONE);
        String last = App.get().settingsStore.getLastEtpHost();
        int lp = App.get().settingsStore.getLastEtpPort();
        if (!last.isEmpty()) etHost.setText(last);
        if (lp > 0) etPort.setText(String.valueOf(lp));
        updateDeviceChip(null, 0);
    }

    private void doManualConnect() {
        String host = etHost.getText() != null ? etHost.getText().toString().trim() : "";
        int port;
        try {
            port = Integer.parseInt(etPort.getText() != null ? etPort.getText().toString().trim() : "21");
        } catch (NumberFormatException e) {
            port = 21;
        }
        if (host.isEmpty()) {
            Toast.makeText(this, R.string.etp_host_required, Toast.LENGTH_SHORT).show();
            return;
        }

        btnManualConnect.setEnabled(false);
        EtpClient old = client;
        client = new EtpClient(host, port);
        if (old != null) old.close();

        setConnState(ConnState.CONNECTING);
        updateDeviceChip(host, port);

        App.get().ioExecutor.execute(() -> {
            try {
                client.connect();
                App.get().settingsStore.setLastEtpHost(host);
                App.get().settingsStore.setLastEtpPort(port);

                // 自动匿名登录
                try {
                    if (client.login("anonymous", "")) {
                        connectedHost = host;
                        connectedPort = port;
                        handler.post(() -> {
                            cardManualConnect.setVisibility(View.GONE);
                            setConnState(ConnState.LOGGED_IN);
                        });
                        return;
                    }
                } catch (IOException ignored) {}

                // 匿名失败，显示认证栏
                connectedHost = host;
                connectedPort = port;
                handler.post(() -> {
                    btnManualConnect.setEnabled(true);
                    layoutAuth.setVisibility(View.VISIBLE);
                    setConnState(ConnState.CONNECTED);
                });

            } catch (IOException e) {
                handler.post(() -> {
                    btnManualConnect.setEnabled(true);
                    setConnState(ConnState.DISCONNECTED);
                    updateDeviceChip(null, 0);
                    Toast.makeText(this, getString(R.string.etp_connect_failed, e.getMessage()),
                            Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void doManualLogin() {
        if (client == null || !client.isConnected()) {
            Toast.makeText(this, R.string.etp_not_connected, Toast.LENGTH_SHORT).show();
            return;
        }
        String inputUser = etUser.getText() != null ? etUser.getText().toString().trim() : "";
        final String user = inputUser.isEmpty() ? "anonymous" : inputUser;
        final String pass = etPass.getText() != null ? etPass.getText().toString() : "";

        btnLogin.setEnabled(false);
        App.get().ioExecutor.execute(() -> {
            try {
                boolean ok = client.login(user, pass);
                handler.post(() -> {
                    btnLogin.setEnabled(true);
                    if (ok) {
                        cardManualConnect.setVisibility(View.GONE);
                        setConnState(ConnState.LOGGED_IN);
                        Toast.makeText(this, getString(R.string.etp_login_ok, user), Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, R.string.etp_login_failed, Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (IOException e) {
                handler.post(() -> {
                    btnLogin.setEnabled(true);
                    Toast.makeText(this, getString(R.string.etp_login_error, e.getMessage()),
                            Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    // ==================== 自动连接 ====================

    private void autoConnect() {
        String lastHost = App.get().settingsStore.getLastEtpHost();
        int lastPort = App.get().settingsStore.getLastEtpPort();
        if (!lastHost.isEmpty() && lastPort > 0) {
            // 填入输入框
            if (etHost.getText() != null && etHost.getText().toString().trim().isEmpty()) {
                etHost.setText(lastHost);
            }
            if (etPort.getText() != null && etPort.getText().toString().trim().isEmpty()) {
                etPort.setText(String.valueOf(lastPort));
            }
            connectToDevice(lastHost, lastPort);
        } else if (!savedDevices.isEmpty()) {
            // 有设备但没上次连接记录 → 展示设备列表，显示手动卡片
            DiscoveredDevice first = savedDevices.get(0);
            updateDeviceChip(first.host, 0);
            tvConnStatus.setText(R.string.etp_tap_to_connect);
            showManualConnect();
        } else {
            // 无设备 → 显示手动输入
            showManualConnect();
        }
    }

    // ==================== 连接状态 UI ====================

    private void setConnState(ConnState state) {
        connState = state;
        switch (state) {
            case CONNECTING:
                tvConnStatus.setText(R.string.etp_connecting);
                tvConnStatus.setTextColor(getColor(android.R.color.holo_orange_dark));
                break;
            case CONNECTED:
                tvConnStatus.setText(getString(R.string.etp_connected_need_login, connectedHost, connectedPort));
                tvConnStatus.setTextColor(getColor(android.R.color.holo_orange_dark));
                break;
            case LOGGED_IN:
                tvConnStatus.setText(getString(R.string.etp_ready, connectedHost));
                tvConnStatus.setTextColor(getColor(android.R.color.holo_green_dark));
                break;
            case DISCONNECTED:
                tvConnStatus.setText(R.string.etp_disconnected);
                tvConnStatus.setTextColor(getColor(android.R.color.darker_gray));
                break;
        }
    }

    private void updateDeviceChip(String host, int port) {
        if (host != null && !host.isEmpty()) {
            chipDevice.setText(host + (port > 0 ? ":" + port : ""));
        } else {
            chipDevice.setText(R.string.etp_select_device);
        }
    }

    private void applyDisconnectedState() {
        setConnState(ConnState.DISCONNECTED);
        updateDeviceChip(null, 0);
    }

    // ==================== 搜索 ====================

    private void doSearch() {
        if (connState != ConnState.LOGGED_IN) {
            Toast.makeText(this, R.string.etp_not_connected, Toast.LENGTH_SHORT).show();
            return;
        }
        String query = etSearch.getText() != null ? etSearch.getText().toString().trim() : "";
        if (query.isEmpty()) {
            Toast.makeText(this, R.string.etp_query_required, Toast.LENGTH_SHORT).show();
            return;
        }

        // 保存搜索历史
        addSearchHistory(query);

        int columns = EtpClient.SIZE_COLUMN | EtpClient.PATH_COLUMN | EtpClient.DATE_MODIFIED_COLUMN;

        btnSearch.setEnabled(false);
        progressSearch.setVisibility(View.VISIBLE);

        App.get().ioExecutor.execute(() -> {
            try {
                List<EtpClient.EtpSearchResult> results = client.query(query, columns);
                handler.post(() -> {
                    btnSearch.setEnabled(true);
                    progressSearch.setVisibility(View.GONE);
                    resultAdapter.setResults(results);
                    if (results.isEmpty()) {
                        showEmpty(getString(R.string.etp_no_results), "");
                    } else {
                        layoutEmpty.setVisibility(View.GONE);
                        rvResults.setVisibility(View.VISIBLE);
                    }
                });
            } catch (IOException e) {
                handler.post(() -> {
                    btnSearch.setEnabled(true);
                    progressSearch.setVisibility(View.GONE);
                    setConnState(ConnState.DISCONNECTED);
                    Toast.makeText(this, getString(R.string.etp_search_error, e.getMessage()),
                            Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void showEmpty(String title, String hint) {
        layoutEmpty.setVisibility(View.VISIBLE);
        rvResults.setVisibility(View.GONE);
        tvEmptyTitle.setText(title);
        if (!hint.isEmpty()) tvEmptyHint.setText(hint);
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
        // 去重：移除旧的同名项
        searchHistory.remove(query);
        searchHistory.add(query);

        // 保持最多 10 条
        List<String> trimmed = new ArrayList<>(searchHistory);
        if (trimmed.size() > 10) {
            searchHistory.clear();
            for (int i = trimmed.size() - 10; i < trimmed.size(); i++) {
                searchHistory.add(trimmed.get(i));
            }
        }

        // 持久化
        org.json.JSONArray arr = new org.json.JSONArray();
        List<String> list = new ArrayList<>(searchHistory);
        // 反序输出，最新的在前
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

        // 标签
        TextView label = new TextView(this);
        label.setText("最近：");
        label.setTextSize(13);
        label.setTextColor(getColor(android.R.color.secondary_text_dark));
        label.setGravity(android.view.Gravity.CENTER_VERTICAL);
        ChipGroup.LayoutParams lp = new ChipGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cgHistory.addView(label, lp);

        // 历史 chips（最新在前）
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

    // ==================== 结果项点击：复制路径 ====================

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

            String fullPath = "";
            if (r.path != null && !r.path.isEmpty()) {
                holder.tvPath.setVisibility(View.VISIBLE);
                fullPath = r.path + "\\" + r.name;
                holder.tvPath.setText(fullPath);
            } else {
                holder.tvPath.setVisibility(View.GONE);
                fullPath = r.name;
            }

            // 点击复制完整路径
            final String copyPath = fullPath;
            holder.itemView.setOnClickListener(v -> {
                ClipboardManager cm = (ClipboardManager) v.getContext().getSystemService(CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("file_path", copyPath));
                Toast.makeText(v.getContext(), "已复制: " + copyPath, Toast.LENGTH_SHORT).show();
            });

            holder.itemView.setOnLongClickListener(v -> {
                // 长按可以扩展：分享路径等
                return false;
            });
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

    // ==================== 生命周期 ====================

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (client != null) {
            client.close();
            client = null;
        }
    }
}
