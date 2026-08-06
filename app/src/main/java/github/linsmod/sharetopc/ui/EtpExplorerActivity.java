package github.linsmod.sharetopc.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.text.style.ImageSpan;
import android.util.TypedValue;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.graphics.drawable.DrawableCompat;
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
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

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
    public static final String EXTRA_DEVICE_ID = "device_id";

    private EtpExplorerViewModel viewModel;
    private EtpExplorerAdapter adapter;
    private LinearLayoutManager layoutManager;

    private String deviceName;
    private String deviceHost;
    private String deviceId;

    // 分类入口
    private View layoutCategories;
    private ChipGroup cgCategories;
    private TextView tvCacheInfo;

    // 本地筛选/排序（数据源为缓存，全部在内存中完成）
    private View tilExplorerSearch;
    private TextInputEditText etExplorerSearch;
    private View layoutExplorerSort;
    private TextView tvEmpty;
    private List<EtpExplorerViewModel.CategoryResult> fullCategories;
    private final Set<String> selectedCategories = new HashSet<>();
    private String searchQuery = "";
    private String currentSort;
    private boolean sortAsc = true;
    private SortItem[] sortItems;

    private static final String[] SORT_NAMES = {
            "name", "size", "path", "extension", "date_modified", "date_created"};

    private static class SortItem {
        final TextView view;
        final String sortName;
        final CharSequence label;
        int state; // 0=none, 1=asc, 2=desc

        SortItem(TextView view, String sortName) {
            this.view = view;
            this.sortName = sortName;
            this.label = view.getText();
        }
    }

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
        deviceId = intent.getStringExtra(EXTRA_DEVICE_ID);
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

        tilExplorerSearch = findViewById(R.id.tilExplorerSearch);
        etExplorerSearch = findViewById(R.id.etExplorerSearch);
        etExplorerSearch.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                searchQuery = s != null ? s.toString() : "";
                rebuildView();
            }
        });

        layoutExplorerSort = findViewById(R.id.layoutExplorerSort);
        initSortItems();

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
        tvEmpty = findViewById(R.id.tvEmpty);
        findViewById(R.id.btnRetry).setOnClickListener(v -> viewModel.refresh());
        findViewById(R.id.btnGoSearch).setOnClickListener(v -> openSearch());
    }

    private void initViewModel() {
        viewModel = new ViewModelProvider(this).get(EtpExplorerViewModel.class);
        viewModel.observe(this::render);
        viewModel.init(deviceId, deviceHost, App.get().settingsStore.getEtpPort());
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
        tilExplorerSearch.setVisibility(View.GONE);
        layoutExplorerSort.setVisibility(View.GONE);

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

        fullCategories = EtpExplorerViewModel.nonEmpty(r.categories);
        // 数据刷新后清理已失效的分类选中
        Set<String> labels = new HashSet<>();
        for (EtpExplorerViewModel.CategoryResult c : fullCategories) labels.add(c.label);
        selectedCategories.retainAll(labels);

        if (fullCategories.isEmpty()) {
            // 全部分类无结果 → 空界面
            rvExplorer.setVisibility(View.GONE);
            layoutCategories.setVisibility(View.GONE);
            tvCacheInfo.setVisibility(View.GONE);
            tilExplorerSearch.setVisibility(View.GONE);
            layoutExplorerSort.setVisibility(View.GONE);
            tvEmpty.setText(R.string.etp_explorer_empty);
            layoutEmpty.setVisibility(View.VISIBLE);
            return;
        }

        layoutEmpty.setVisibility(View.GONE);
        tilExplorerSearch.setVisibility(View.VISIBLE);
        layoutExplorerSort.setVisibility(View.VISIBLE);
        rebuildView();

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
        tilExplorerSearch.setVisibility(View.GONE);
        layoutExplorerSort.setVisibility(View.GONE);
        tvError.setText(e.message);
        layoutError.setVisibility(View.VISIBLE);
    }

    // ==================== 分类筛选 / 本地搜索 / 排序 ====================

    /** 根据 分类选中 + 搜索词 + 排序 重算列表与 chips。 */
    private void rebuildView() {
        if (fullCategories == null) return;
        List<String> terms = EtpExplorerViewModel.parseTerms(searchQuery);
        adapter.setHighlightTerms(terms);

        // chips 始终展示全部可用分类（仅受搜索影响，不受选中影响），便于多选
        List<EtpExplorerViewModel.CategoryResult> chipView =
                EtpExplorerViewModel.applyView(fullCategories, null,
                        searchQuery, currentSort, sortAsc);
        List<EtpExplorerViewModel.CategoryResult> view =
                EtpExplorerViewModel.applyView(fullCategories, selectedCategories,
                        searchQuery, currentSort, sortAsc);

        if (chipView.isEmpty()) {
            layoutCategories.setVisibility(View.GONE);
        } else {
            buildCategoryChips(chipView);
        }

        if (view.isEmpty()) {
            rvExplorer.setVisibility(View.GONE);
            adapter.submitCategories(new ArrayList<>());
            tvEmpty.setText(searchQuery.isEmpty() && selectedCategories.isEmpty()
                    ? R.string.etp_explorer_empty : R.string.etp_explorer_no_match);
            layoutEmpty.setVisibility(View.VISIBLE);
            return;
        }
        layoutEmpty.setVisibility(View.GONE);
        adapter.submitCategories(view);
        rvExplorer.setVisibility(View.VISIBLE);
    }

    /** 渲染分类筛选 chips（可多选）：勾选后仅显示选中分类，全部不勾选时显示全部。 */
    private void buildCategoryChips(List<EtpExplorerViewModel.CategoryResult> categories) {
        cgCategories.removeAllViews();
        layoutCategories.setVisibility(View.VISIBLE);
        for (EtpExplorerViewModel.CategoryResult c : categories) {
            Chip chip = new Chip(this, null, com.google.android.material.R.attr.chipStyle);
            chip.setText(c.label + " " + c.items.size());
            chip.setCheckable(true);
            chip.setChecked(selectedCategories.contains(c.label));
            chip.setOnCheckedChangeListener((button, isChecked) -> {
                if (isChecked) selectedCategories.add(c.label);
                else selectedCategories.remove(c.label);
                rebuildView();
            });
            cgCategories.addView(chip);
        }
    }

    // ---- 排序（参考搜索页：点击排序字段，单选中，再点切换升降序） ----

    private void initSortItems() {
        TextView[] allViews = {
                findViewById(R.id.tvSortName), findViewById(R.id.tvSortSize),
                findViewById(R.id.tvSortPath), findViewById(R.id.tvSortExt),
                findViewById(R.id.tvSortDateMod), findViewById(R.id.tvSortDateCre)};
        List<SortItem> items = new ArrayList<>();
        for (int i = 0; i < SORT_NAMES.length; i++) {
            TextView view = allViews[i];
            SortItem item = new SortItem(view, SORT_NAMES[i]);
            items.add(item);
            final int idx = items.size() - 1;
            view.setOnClickListener(v -> onSortItemClicked(idx));
        }
        sortItems = items.toArray(new SortItem[0]);
        refreshSortIndicators();
    }

    private void onSortItemClicked(int index) {
        SortItem clicked = sortItems[index];
        if (clicked.state == 0) {
            // 单选：取消其他项激活，激活当前项并沿用当前方向
            for (SortItem item : sortItems) {
                if (item != clicked && item.state != 0) item.state = 0;
            }
            clicked.state = sortAsc ? 1 : 2;
            currentSort = clicked.sortName;
        } else {
            // 已激活：切换升/降序
            clicked.state = clicked.state == 1 ? 2 : 1;
        }
        sortAsc = clicked.state == 1;
        refreshSortIndicators();
        rebuildView();
    }

    private void refreshSortIndicators() {
        TypedValue tv = new TypedValue();
        getTheme().resolveAttribute(
                com.google.android.material.R.attr.colorPrimary, tv, true);
        int accent = tv.data;
        tv = new TypedValue();
        getTheme().resolveAttribute(
                com.google.android.material.R.attr.colorOnSurface, tv, true);
        int subtle = (tv.data & 0x00FFFFFF) | 0x60000000;

        float density = getResources().getDisplayMetrics().density;
        float textHeight = sortItems[0].view.getPaint().getFontMetrics().descent
                - sortItems[0].view.getPaint().getFontMetrics().ascent;
        int iconSize = (int) (textHeight * 0.6f);

        for (SortItem item : sortItems) {
            int resId;
            int iconColor;
            if (item.state == 1) {
                resId = R.drawable.ic_sort_asc; iconColor = accent;
            } else if (item.state == 2) {
                resId = R.drawable.ic_sort_desc; iconColor = accent;
            } else {
                resId = R.drawable.ic_sort_both; iconColor = subtle;
            }

            item.view.setTextColor(item.state != 0 ? accent : subtle);
            item.view.setTypeface(null, item.state != 0 ? Typeface.BOLD : Typeface.NORMAL);
            item.view.setBackground(null);
            int vPad = (int) (density * 2);
            item.view.setPadding(0, vPad, 0, vPad);

            Drawable icon = makeIcon(resId, iconColor, iconSize);
            SpannableString ss = new SpannableString(item.label + "  ");
            if (icon != null) {
                ImageSpan span = new ImageSpan(icon, ImageSpan.ALIGN_CENTER);
                ss.setSpan(span, ss.length() - 1, ss.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            item.view.setText(ss);
        }
    }

    private Drawable makeIcon(int resId, int color, int size) {
        Drawable d = ContextCompat.getDrawable(this, resId);
        if (d == null) return null;
        d = DrawableCompat.wrap(d).mutate();
        DrawableCompat.setTint(d, color);
        d.setBounds(0, 0, size, size);
        return d;
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
