package github.linsmod.sharetopc.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.TypedValue;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
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

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import github.linsmod.sharetopc.App;
import github.linsmod.sharetopc.R;
import github.linsmod.sharetopc.transfer.EtpClient;

/**
 * ETP 文件浏览：打开后在常用位置按分类搜索常见类型并缓存结果，
 * 分类展示给用户；无缓存时显示"正在初始化缓存"进度界面。
 * 工具栏提供"刷新"与"进入搜索"入口。
 */
public class EtpExplorerActivity extends androidx.appcompat.app.AppCompatActivity {

    public static final String EXTRA_DEVICE_NAME = "device_name";
    public static final String EXTRA_HOST = "host";

    private EtpExplorerViewModel viewModel;
    private EtpExplorerAdapter adapter;
    private LinearLayoutManager layoutManager;

    private String deviceName;
    private String deviceHost;

    // 分类入口
    private View layoutCategories;
    private ChipGroup cgCategories;
    private TextView tvCacheInfo;

    private RecyclerView rvExplorer;
    private View layoutLoading;
    private TextView tvLoadingTitle, tvLoadingDetail;
    private View layoutError;
    private TextView tvError;
    private View layoutEmpty;

    private final SimpleDateFormat timeFormat =
            new SimpleDateFormat("HH:mm", Locale.getDefault());

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_etp_explorer);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.etpExplorerRoot), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        Intent intent = getIntent();
        deviceName = intent.getStringExtra(EXTRA_DEVICE_NAME);
        deviceHost = intent.getStringExtra(EXTRA_HOST);
        if (TextUtils.isEmpty(deviceHost)) {
            Toast.makeText(this, "未指定设备", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        initViews();
        initViewModel();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        viewModel.removeObserver();
    }

    // ==================== 初始化 ====================

    private void initViews() {
        androidx.appcompat.widget.Toolbar toolbar = findViewById(R.id.toolbarEtpExplorer);
        toolbar.setTitle(getString(R.string.etp_explorer_title_format,
                deviceName != null ? deviceName : ""));
        toolbar.setNavigationOnClickListener(v -> finish());
        toolbar.inflateMenu(R.menu.etp_explorer_menu);
        toolbar.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == R.id.action_explorer_refresh) {
                viewModel.refresh();
                return true;
            }
            if (id == R.id.action_explorer_search) {
                openSearch();
                return true;
            }
            return false;
        });

        layoutCategories = findViewById(R.id.layoutCategories);
        cgCategories = findViewById(R.id.cgCategories);
        tvCacheInfo = findViewById(R.id.tvCacheInfo);

        rvExplorer = findViewById(R.id.rvExplorer);
        layoutManager = new LinearLayoutManager(this);
        rvExplorer.setLayoutManager(layoutManager);
        rvExplorer.addItemDecoration(new MaterialDividerItemDecoration(this,
                MaterialDividerItemDecoration.VERTICAL));
        rvExplorer.setItemAnimator(null);

        adapter = new EtpExplorerAdapter(this::showFilePropertiesDialog);
        rvExplorer.setAdapter(adapter);

        layoutLoading = findViewById(R.id.layoutLoading);
        tvLoadingTitle = findViewById(R.id.tvLoadingTitle);
        tvLoadingDetail = findViewById(R.id.tvLoadingDetail);
        layoutError = findViewById(R.id.layoutError);
        tvError = findViewById(R.id.tvError);
        layoutEmpty = findViewById(R.id.layoutEmpty);
        findViewById(R.id.btnRetry).setOnClickListener(v -> viewModel.refresh());
        findViewById(R.id.btnGoSearch).setOnClickListener(v -> openSearch());
    }

    private void initViewModel() {
        viewModel = new ViewModelProvider(this).get(EtpExplorerViewModel.class);
        viewModel.observe(this::render);
        viewModel.init(deviceHost, App.get().settingsStore.getEtpPort());
    }

    // ==================== 状态渲染 ====================

    private void render(EtpExplorerViewModel.State state) {
        if (state instanceof EtpExplorerViewModel.Initializing) {
            renderInitializing((EtpExplorerViewModel.Initializing) state);
        } else if (state instanceof EtpExplorerViewModel.Ready) {
            renderReady((EtpExplorerViewModel.Ready) state);
        } else if (state instanceof EtpExplorerViewModel.Error) {
            renderError((EtpExplorerViewModel.Error) state);
        }
    }

    /** 初始化/刷新缓存：全屏进度界面，提示当前正在搜索的分类。 */
    private void renderInitializing(EtpExplorerViewModel.Initializing s) {
        rvExplorer.setVisibility(View.GONE);
        layoutError.setVisibility(View.GONE);
        layoutEmpty.setVisibility(View.GONE);
        layoutCategories.setVisibility(View.GONE);
        tvCacheInfo.setVisibility(View.GONE);

        tvLoadingTitle.setText(s.firstLoad
                ? R.string.etp_explorer_caching : R.string.etp_explorer_refreshing);
        if (s.total > 0 && s.current > 0 && !TextUtils.isEmpty(s.categoryLabel)) {
            tvLoadingDetail.setVisibility(View.VISIBLE);
            tvLoadingDetail.setText(getString(R.string.etp_explorer_progress,
                    s.categoryLabel, s.current, s.total));
        } else {
            tvLoadingDetail.setVisibility(View.GONE);
        }
        layoutLoading.setVisibility(View.VISIBLE);
    }

    private void renderReady(EtpExplorerViewModel.Ready r) {
        layoutLoading.setVisibility(View.GONE);
        layoutError.setVisibility(View.GONE);

        List<EtpExplorerViewModel.CategoryResult> categories =
                EtpExplorerViewModel.nonEmpty(r.categories);

        if (categories.isEmpty()) {
            // 全部分类无结果 → 空界面
            rvExplorer.setVisibility(View.GONE);
            layoutCategories.setVisibility(View.GONE);
            tvCacheInfo.setVisibility(View.GONE);
            layoutEmpty.setVisibility(View.VISIBLE);
            return;
        }

        layoutEmpty.setVisibility(View.GONE);
        buildCategoryChips(categories);
        adapter.submitCategories(categories);
        rvExplorer.setVisibility(View.VISIBLE);

        tvCacheInfo.setVisibility(View.VISIBLE);
        String time = timeFormat.format(new Date(r.cachedAt));
        tvCacheInfo.setText(getString(r.fromCache
                ? R.string.etp_explorer_cached_at : R.string.etp_explorer_updated_at, time));
    }

    private void renderError(EtpExplorerViewModel.Error e) {
        rvExplorer.setVisibility(View.GONE);
        layoutLoading.setVisibility(View.GONE);
        layoutEmpty.setVisibility(View.GONE);
        layoutCategories.setVisibility(View.GONE);
        tvCacheInfo.setVisibility(View.GONE);
        tvError.setText(e.message);
        layoutError.setVisibility(View.VISIBLE);
    }

    // ==================== 分类 chips ====================

    /** 渲染分类入口 chips（仅非空分类），点击滚动到对应分节。 */
    private void buildCategoryChips(List<EtpExplorerViewModel.CategoryResult> categories) {
        cgCategories.removeAllViews();
        List<Integer> headerPositions = adapter.getHeaderPositions();
        layoutCategories.setVisibility(View.VISIBLE);
        for (int i = 0; i < categories.size(); i++) {
            EtpExplorerViewModel.CategoryResult c = categories.get(i);
            Chip chip = new Chip(this, null, com.google.android.material.R.attr.chipStyle);
            chip.setText(c.label + " " + c.items.size());
            chip.setCheckable(false);
            final int section = i;
            chip.setOnClickListener(v -> {
                if (section < headerPositions.size()) {
                    layoutManager.scrollToPosition(headerPositions.get(section));
                }
            });
            cgCategories.addView(chip);
        }
    }

    // ==================== 跳转搜索 ====================

    private void openSearch() {
        Intent intent = new Intent(this, EtpSearchActivity.class);
        intent.putExtra(EtpSearchActivity.EXTRA_DEVICE_NAME, deviceName);
        intent.putExtra(EtpSearchActivity.EXTRA_HOST, deviceHost);
        startActivity(intent);
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
