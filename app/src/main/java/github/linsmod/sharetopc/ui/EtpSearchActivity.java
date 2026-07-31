package github.linsmod.sharetopc.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.divider.MaterialDividerItemDecoration;
import com.google.android.material.textfield.TextInputEditText;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import github.linsmod.sharetopc.App;
import github.linsmod.sharetopc.R;
import github.linsmod.sharetopc.transfer.EtpClient;

/**
 * ETP 远程文件搜索界面（重构版）。
 * View 层只负责渲染状态、处理用户交互，业务逻辑全在 ViewModel。
 */
public class EtpSearchActivity extends androidx.appcompat.app.AppCompatActivity {

    public static final String EXTRA_DEVICE_NAME = "device_name";
    public static final String EXTRA_HOST = "host";

    private EtpSearchViewModel viewModel;

    // View 引用
    private TextInputEditText etSearch;
    private ChipGroup cgHistory;
    private ProgressBar progressSearch;
    private RecyclerView rvResults;
    private View layoutEmpty;
    private TextView tvEmptyTitle;
    private TextView tvEmptyHint;
    private TextView tvResultCount;

    private EtpResultAdapter resultAdapter;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable debounceSearch;

    // ==================== 生命周期 ====================

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

        Intent intent = getIntent();
        String deviceName = intent.getStringExtra(EXTRA_DEVICE_NAME);
        String deviceHost = intent.getStringExtra(EXTRA_HOST);
        int etpPort = App.get().settingsStore.getEtpPort();
        if (TextUtils.isEmpty(deviceHost)) {
            Toast.makeText(this, "未指定设备", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        initViews(deviceName);
        initDebounce();
        initViewModel(deviceHost, etpPort);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        viewModel.removeObserver();
        viewModel.disconnect();
    }

    // ==================== 初始化 ====================

    private void initViews(String deviceName) {
        androidx.appcompat.widget.Toolbar toolbar = findViewById(R.id.toolbarEtpSearch);
        toolbar.setTitle(getString(R.string.etp_search_title_format,
                deviceName != null ? deviceName : ""));
        toolbar.setNavigationOnClickListener(v -> finish());
        // 右上角设置按钮 → 完整设置页（端口管理等）
        toolbar.inflateMenu(R.menu.etp_search_menu);
        toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.action_etp_settings) {
                startActivity(new Intent(this, EtpSettingsActivity.class));
                return true;
            }
            return false;
        });

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

        resultAdapter = new EtpResultAdapter(this::showFilePropertiesDialog);
        rvResults.setAdapter(resultAdapter);

        // 上拉加载更多
        rvResults.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                if (dy > 0) {
                    LinearLayoutManager lm = (LinearLayoutManager) recyclerView.getLayoutManager();
                    if (lm != null && lm.findLastVisibleItemPosition()
                            >= resultAdapter.getItemCount() - 3) {
                        viewModel.loadMore();
                    }
                }
            }
        });

        // 筛选 FAB → BottomSheet
        findViewById(R.id.fabPathPreset).setOnClickListener(v -> showFilterSheet());
    }

    private void initDebounce() {
        debounceSearch = () -> {
            String query = etSearch.getText() != null
                    ? etSearch.getText().toString().trim() : "";
            if (!query.isEmpty()) viewModel.search(query);
        };

        etSearch.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(android.text.Editable s) {
                handler.removeCallbacks(debounceSearch);
                String q = s.toString().trim();
                if (q.isEmpty()) {
                    renderResult(null); // 回退到空状态
                    return;
                }
                handler.postDelayed(debounceSearch, 300);
            }
        });
    }

    private void initViewModel(String host, int port) {
        viewModel = new ViewModelProvider(this).get(EtpSearchViewModel.class);

        viewModel.observe(state -> {
            if (state instanceof SearchUiState.Connecting) {
                renderConnecting();
            } else if (state instanceof SearchUiState.Ready) {
                renderReady();
            } else if (state instanceof SearchUiState.Searching) {
                renderSearching(((SearchUiState.Searching) state).query);
            } else if (state instanceof SearchUiState.HasResults) {
                renderResult((SearchUiState.HasResults) state);
            } else if (state instanceof SearchUiState.Empty) {
                renderEmpty(((SearchUiState.Empty) state).query);
            } else if (state instanceof SearchUiState.Error) {
                renderError(((SearchUiState.Error) state).message);
            }
        });

        viewModel.connect(host, port);
    }

    // ==================== 状态渲染 ====================

    private void renderConnecting() {
        progressSearch.setVisibility(View.VISIBLE);
        rvResults.setVisibility(View.GONE);
        layoutEmpty.setVisibility(View.VISIBLE);
        tvResultCount.setVisibility(View.GONE);
        tvEmptyTitle.setText("连接中…");
        tvEmptyHint.setVisibility(View.GONE);
        refreshHistoryChips();
    }

    private void renderReady() {
        progressSearch.setVisibility(View.GONE);
        rvResults.setVisibility(View.GONE);
        layoutEmpty.setVisibility(View.VISIBLE);
        tvResultCount.setVisibility(View.GONE);
        tvEmptyTitle.setText(getString(R.string.etp_empty_title));
        tvEmptyHint.setVisibility(View.VISIBLE);
        refreshHistoryChips();
    }

    private void renderSearching(String query) {
        progressSearch.setVisibility(View.VISIBLE);
        rvResults.setVisibility(View.GONE);
        layoutEmpty.setVisibility(View.GONE);
        tvResultCount.setVisibility(View.VISIBLE);
        tvResultCount.setText(getString(R.string.etp_searching));
    }

    private void renderResult(SearchUiState.HasResults r) {
        progressSearch.setVisibility(View.GONE);
        layoutEmpty.setVisibility(View.GONE);
        rvResults.setVisibility(View.VISIBLE);
        tvResultCount.setVisibility(View.VISIBLE);

        if (r == null) {
            // 空输入 → 回到 Ready
            resultAdapter.submitList(null);
            resultAdapter.setShowFooter(false);
            renderReady();
            return;
        }

        if (r.items.isEmpty()) {
            renderEmpty(r.query);
            return;
        }

        tvResultCount.setText(getString(R.string.etp_result_count, r.totalCount));
        resultAdapter.submitList(r.items);
        resultAdapter.setShowFooter(r.hasMore);
    }

    private void renderEmpty(String query) {
        progressSearch.setVisibility(View.GONE);
        rvResults.setVisibility(View.GONE);
        layoutEmpty.setVisibility(View.VISIBLE);
        tvResultCount.setVisibility(View.GONE);
        tvEmptyTitle.setText(getString(R.string.etp_no_results));
        tvEmptyHint.setVisibility(View.GONE);
        resultAdapter.submitList(null);
    }

    private void renderError(String message) {
        progressSearch.setVisibility(View.GONE);
        tvResultCount.setVisibility(View.GONE);
        resultAdapter.submitList(null);
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        // 保持当前可见内容不变
    }

    // ==================== 搜索历史 Chips ====================

    private void refreshHistoryChips() {
        cgHistory.removeAllViews();
        List<String> history = viewModel.getHistoryReversed();
        if (history.isEmpty()) {
            cgHistory.setVisibility(View.GONE);
            return;
        }
        cgHistory.setVisibility(View.VISIBLE);
        for (int i = 0; i < history.size(); i++) {
            String q = history.get(i);
            Chip chip = new Chip(this, null, com.google.android.material.R.attr.chipStyle);
            chip.setText(q);
            chip.setClickable(true);
            chip.setCheckable(false);
            chip.setChipIconResource(android.R.drawable.ic_menu_recent_history);
            chip.setCloseIconVisible(true);
            chip.setOnClickListener(v -> {
                etSearch.setText(q);
                viewModel.search(q);
            });
            chip.setOnCloseIconClickListener(v -> {
                viewModel.removeHistory(q);
                refreshHistoryChips();
            });
            cgHistory.addView(chip);
        }
    }

    // ==================== 筛选 BottomSheet ====================

    private void showFilterSheet() {
        EtpFilterSheet sheet = new EtpFilterSheet();
        sheet.setOnFilterApplied(() -> viewModel.onFilterChanged());
        sheet.show(getSupportFragmentManager(), "etp_filter");
    }

    // ==================== 文件属性对话框 ====================

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
            body.append("修改时间：")
                    .append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                            .format(new Date(millis)))
                    .append("\n\n");
        }
        if (r.dateCreated > 0) {
            long millis = EtpClient.EtpSearchResult.filetimeToMillis(r.dateCreated);
            body.append("创建时间：")
                    .append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                            .format(new Date(millis)))
                    .append("\n\n");
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
}
