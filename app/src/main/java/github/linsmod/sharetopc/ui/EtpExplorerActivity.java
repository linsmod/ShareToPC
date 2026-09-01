package github.linsmod.sharetopc.ui;

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
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
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

import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
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
    public static final String EXTRA_PORT = "port";

    private EtpExplorerViewModel viewModel;
    private EtpExplorerAdapter adapter;
    private EtpBrowseViewModel browseViewModel;
    private EtpResultAdapter browseAdapter;
    private LinearLayoutManager layoutManager;

    // 当前模式：false = 常用文件（缓存+搜索），true = 位置浏览（逐级目录）
    private boolean browseMode;
    private boolean modeSwitchGuard;
    /** 是否已渲染过浏览内容（用于加载中决定保留旧列表还是全屏转圈）。 */
    private boolean browseRendered;
    /** 缓存模式最近一次状态快照（切回时用于重渲染）。 */
    private EtpExplorerViewModel.State cacheState;

    // 模式切换与浏览 UI
    private MaterialButtonToggleGroup tgExplorerMode;
    private MaterialButton btnModeCommon, btnModeBrowse;
    private android.widget.HorizontalScrollView layoutBreadcrumb;
    private LinearLayout llBreadcrumb;
    private com.google.android.material.floatingactionbutton.FloatingActionButton fabQuickLocations;
    private com.google.android.material.floatingactionbutton.FloatingActionButton fabCategories;

    private String deviceName;
    private String deviceHost;
    private String deviceId;
    private int devicePort;

    // 分类入口
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
    private ProgressBar pbBrowseLoading;
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
        devicePort = intent.getIntExtra(EXTRA_PORT, 0);
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
        browseViewModel.removeObserver();
    }

    // ==================== 初始化 ====================

    private void initViews() {
        androidx.appcompat.widget.Toolbar toolbar = findViewById(R.id.toolbarEtpExplorer);
        toolbar.setTitle(getString(R.string.etp_explorer_title_format,
                deviceName != null ? deviceName : ""));
        toolbar.setNavigationOnClickListener(v -> {
            if (browseMode && !browseViewModel.isAtRoot()) {
                browseViewModel.goUp();
            } else {
                finish();
            }
        });
        toolbar.inflateMenu(R.menu.etp_explorer_menu);
        toolbar.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == R.id.action_explorer_refresh) {
                if (browseMode) {
                    browseViewModel.refreshCurrent();
                } else {
                    viewModel.refresh();
                }
                return true;
            }
            if (id == R.id.action_explorer_search) {
                openSearch();
                return true;
            }
            return false;
        });

        tvCacheInfo = findViewById(R.id.tvCacheInfo);
        fabCategories = findViewById(R.id.fabCategories);

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

        // 模式切换
        tgExplorerMode = findViewById(R.id.tgExplorerMode);
        btnModeCommon = findViewById(R.id.btnModeCommon);
        btnModeBrowse = findViewById(R.id.btnModeBrowse);
        tgExplorerMode.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked || modeSwitchGuard) return;
            applyMode(checkedId == R.id.btnModeBrowse);
        });

        // 浏览模式：面包屑 + 目录列表适配器
        layoutBreadcrumb = findViewById(R.id.layoutBreadcrumb);
        llBreadcrumb = findViewById(R.id.llBreadcrumb);
        fabQuickLocations = findViewById(R.id.fabQuickLocations);
        browseAdapter = new EtpResultAdapter(this::onBrowseItemClick, false /* showPathInfo */);
        browseAdapter.setItemLongClickListener(item -> {
            showFilePropertiesDialog(item);
            return true;
        });
        browseAdapter.setOnLoadMoreListener(() -> browseViewModel.loadMore());

        rvExplorer = findViewById(R.id.rvExplorer);
        layoutManager = new LinearLayoutManager(this);
        rvExplorer.setLayoutManager(layoutManager);
        rvExplorer.addItemDecoration(new MaterialDividerItemDecoration(this,
                MaterialDividerItemDecoration.VERTICAL));
        rvExplorer.setItemAnimator(null);

        adapter = new EtpExplorerAdapter(item -> {
            if (item.isFolder) {
                // 常用文件里点文件夹 → 切到位置浏览并进入该目录
                enterBrowseAt(item.fullPath());
            } else {
                EtpFileDialog.showActions(this, item, deviceId, deviceHost, devicePort,
                        () -> enterBrowseAt(item.path));
            }
        });
        adapter.setOnItemLongClickListener(item -> {
            showFilePropertiesDialog(item);
            return true;
        });
        rvExplorer.setAdapter(adapter);

        layoutLoading = findViewById(R.id.layoutLoading);
        pbBrowseLoading = findViewById(R.id.pbBrowseLoading);
        tvLoadingTitle = findViewById(R.id.tvLoadingTitle);
        tvLoadingDetail = findViewById(R.id.tvLoadingDetail);
        layoutError = findViewById(R.id.layoutError);
        tvError = findViewById(R.id.tvError);
        layoutEmpty = findViewById(R.id.layoutEmpty);
        tvEmpty = findViewById(R.id.tvEmpty);
        findViewById(R.id.btnRetry).setOnClickListener(v -> {
            if (browseMode) {
                browseViewModel.refreshCurrent();
            } else {
                viewModel.refresh();
            }
        });
        findViewById(R.id.btnGoSearch).setOnClickListener(v -> openSearch());
    }

    private void initViewModel() {
        viewModel = new ViewModelProvider(this).get(EtpExplorerViewModel.class);
        viewModel.observe(this::render);
        viewModel.init(deviceId, deviceHost, App.get().settingsStore.getEtpPort());

        browseViewModel = new ViewModelProvider(this).get(EtpBrowseViewModel.class);
        browseViewModel.observe(this::renderBrowseState);
        browseViewModel.init(deviceHost, App.get().settingsStore.getEtpPort());
    }

    // ==================== 状态渲染 ====================

    private void render(EtpExplorerViewModel.State state) {
        cacheState = state;
        if (browseMode) return;
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
        if (browseMode) return;
        rvExplorer.setVisibility(View.GONE);
        layoutError.setVisibility(View.GONE);
        layoutEmpty.setVisibility(View.GONE);
        fabCategories.setVisibility(View.GONE);
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
        if (browseMode) return;
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
            fabCategories.setVisibility(View.GONE);
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
        if (browseMode) return;
        rvExplorer.setVisibility(View.GONE);
        layoutLoading.setVisibility(View.GONE);
        layoutEmpty.setVisibility(View.GONE);
        fabCategories.setVisibility(View.GONE);
        tvCacheInfo.setVisibility(View.GONE);
        tilExplorerSearch.setVisibility(View.GONE);
        layoutExplorerSort.setVisibility(View.GONE);
        tvError.setText(e.message);
        layoutError.setVisibility(View.VISIBLE);
    }

    // ==================== 双模式：常用文件 / 位置浏览 ====================

    @Override
    public void onBackPressed() {
        if (browseMode && !browseViewModel.isAtRoot()) {
            browseViewModel.goUp();
            return;
        }
        super.onBackPressed();
    }

    /** 应用指定模式：切换 rvExplorer 的 adapter 与顶部区块。 */
    private void applyMode(boolean browse) {
        if (browseMode == browse) return;
        browseMode = browse;
        if (browse) {
            showBrowseMode();
        } else {
            showCacheMode();
        }
    }

    private void showCacheMode() {
        modeSwitchGuard = true;
        btnModeCommon.setChecked(true);
        modeSwitchGuard = false;
        layoutBreadcrumb.setVisibility(View.GONE);
        fabQuickLocations.setVisibility(View.GONE);
        pbBrowseLoading.setVisibility(View.GONE);
        rvExplorer.setAdapter(adapter);
        if (cacheState != null) {
            render(cacheState);
        }
    }

    private void showBrowseMode() {
        modeSwitchGuard = true;
        btnModeBrowse.setChecked(true);
        modeSwitchGuard = false;
        fabCategories.setVisibility(View.GONE);
        tvCacheInfo.setVisibility(View.GONE);
        tilExplorerSearch.setVisibility(View.GONE);
        layoutExplorerSort.setVisibility(View.GONE);
        rvExplorer.setAdapter(browseAdapter);
        renderBrowseState(browseViewModel.snapshot());
    }

    /** 从"常用文件"点击文件夹 → 进入浏览模式并打开该目录。 */
    private void enterBrowseAt(String path) {
        if (!browseMode) applyMode(true);
        browseViewModel.openFolder(path);
    }

    // ---- 浏览模式渲染 ----

    private void renderBrowseState(EtpBrowseViewModel.State s) {
        if (!browseMode || s == null) return;
        buildBreadcrumb();
        fabQuickLocations.setVisibility(View.GONE);
        pbBrowseLoading.setVisibility(View.GONE);
        if (s instanceof EtpBrowseViewModel.Root) {
            renderBrowseRoot((EtpBrowseViewModel.Root) s);
        } else if (s instanceof EtpBrowseViewModel.Loading) {
            renderBrowseLoading();
        } else if (s instanceof EtpBrowseViewModel.Folder) {
            renderBrowseFolder((EtpBrowseViewModel.Folder) s);
        } else if (s instanceof EtpBrowseViewModel.LoadMoreState) {
            renderBrowseLoadMore((EtpBrowseViewModel.LoadMoreState) s);
        } else if (s instanceof EtpBrowseViewModel.Error) {
            renderBrowseError((EtpBrowseViewModel.Error) s);
        }
    }

    private void renderBrowseRoot(EtpBrowseViewModel.Root r) {
        browseRendered = true;
        layoutLoading.setVisibility(View.GONE);
        layoutError.setVisibility(View.GONE);
        buildQuickChips(r.quickLocations);
        if (r.drives.isEmpty()) {
            rvExplorer.setVisibility(View.GONE);
            browseAdapter.submitList(new ArrayList<>());
            browseAdapter.setFooterState(EtpResultAdapter.FOOTER_HIDDEN, 0);
            tvEmpty.setText(R.string.etp_browse_no_drive);
            layoutEmpty.setVisibility(View.VISIBLE);
            return;
        }
        layoutEmpty.setVisibility(View.GONE);
        List<EtpClient.EtpSearchResult> drives = new ArrayList<>();
        for (EtpBrowseViewModel.DriveItem d : r.drives) {
            // 磁盘行：把真实盘符路径放进 fileListFilename（该列在浏览查询中未使用），供点击进入
            drives.add(new EtpClient.EtpSearchResult(
                    true, d.label, d.path, -1, -1, -1, -1, d.path, -1));
        }
        browseAdapter.submitList(drives);
        browseAdapter.setFooterState(EtpResultAdapter.FOOTER_HIDDEN, 0);
        rvExplorer.setVisibility(View.VISIBLE);
    }

    private void renderBrowseLoading() {
        if (browseRendered) {
            // 保留当前内容，顶部细进度条示意加载中，请求完成后再整体替换
            layoutLoading.setVisibility(View.GONE);
            pbBrowseLoading.setVisibility(View.VISIBLE);
            return;
        }
        layoutError.setVisibility(View.GONE);
        layoutEmpty.setVisibility(View.GONE);
        rvExplorer.setVisibility(View.GONE);
        tvLoadingTitle.setText(R.string.etp_browse_loading);
        tvLoadingDetail.setVisibility(View.GONE);
        layoutLoading.setVisibility(View.VISIBLE);
    }

    private void renderBrowseFolder(EtpBrowseViewModel.Folder f) {
        browseRendered = true;
        layoutLoading.setVisibility(View.GONE);
        layoutError.setVisibility(View.GONE);
        if (f.items.isEmpty()) {
            rvExplorer.setVisibility(View.GONE);
            browseAdapter.submitList(new ArrayList<>());
            browseAdapter.setFooterState(EtpResultAdapter.FOOTER_HIDDEN, 0);
            tvEmpty.setText(R.string.etp_browse_empty);
            layoutEmpty.setVisibility(View.VISIBLE);
            return;
        }
        layoutEmpty.setVisibility(View.GONE);
        browseAdapter.submitList(f.items);
        browseAdapter.setFooterState(f.hasMore
                        ? EtpResultAdapter.FOOTER_LOAD_MORE : EtpResultAdapter.FOOTER_ALL_LOADED,
                (int) f.totalCount);
        rvExplorer.setVisibility(View.VISIBLE);
    }

    private void renderBrowseLoadMore(EtpBrowseViewModel.LoadMoreState s) {
        browseAdapter.setFooterState(s.loading
                        ? EtpResultAdapter.FOOTER_LOADING
                        : s.failed ? EtpResultAdapter.FOOTER_ERROR : EtpResultAdapter.FOOTER_LOAD_MORE,
                0);
    }

    private void renderBrowseError(EtpBrowseViewModel.Error e) {
        layoutLoading.setVisibility(View.GONE);
        layoutEmpty.setVisibility(View.GONE);
        rvExplorer.setVisibility(View.GONE);
        tvError.setText(e.message);
        layoutError.setVisibility(View.VISIBLE);
    }

    /** 浏览模式点击：文件夹进入（磁盘行用 fileListFilename 携带真实盘符路径），文件弹操作菜单。 */
    private void onBrowseItemClick(EtpClient.EtpSearchResult item) {
        if (item.isFolder) {
            String drivePath = item.fileListFilename;
            browseViewModel.openFolder(drivePath != null && !drivePath.isEmpty()
                    ? drivePath : item.fullPath());
        } else {
            EtpFileDialog.showActions(this, item, deviceId, deviceHost, devicePort,
                    () -> enterBrowseAt(item.path));
        }
    }

    /** 渲染根视图常用位置浮动按钮：点击弹出位置列表菜单。 */
    private void buildQuickChips(List<EtpBrowseViewModel.QuickLocation> locations) {
        if (locations == null || locations.isEmpty()) {
            fabQuickLocations.setVisibility(View.GONE);
            return;
        }
        fabQuickLocations.setVisibility(View.VISIBLE);
        fabQuickLocations.setOnClickListener(v -> {
            PopupMenu popup = new PopupMenu(this, v);
            for (int i = 0; i < locations.size(); i++) {
                EtpBrowseViewModel.QuickLocation loc = locations.get(i);
                popup.getMenu().add(Menu.NONE, i, i, loc.label);
            }
            popup.setOnMenuItemClickListener(item -> {
                int idx = item.getItemId();
                if (idx >= 0 && idx < locations.size()) {
                    browseViewModel.openFolder(locations.get(idx).path);
                    return true;
                }
                return false;
            });
            popup.show();
        });
    }

    /**
     * 渲染面包屑：此电脑 › C: › Users › Desktop（当前段加粗主色，其余可点击跳转）。
     * 内容可能超宽，渲染后自动滚动到最右使当前段可见。
     */
    private void buildBreadcrumb() {
        List<String> labels = browseViewModel.getBreadcrumbLabels();
        llBreadcrumb.removeAllViews();
        layoutBreadcrumb.setVisibility(View.VISIBLE);

        float density = getResources().getDisplayMetrics().density;
        int vPad = (int) (density * 6);
        int sepPad = (int) (density * 5);
        int selBg = 0;
        TypedValue tv = new TypedValue();
        if (getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true)) {
            selBg = tv.resourceId;
        }

        for (int i = 0; i < labels.size(); i++) {
            boolean isLast = i == labels.size() - 1;
            if (i > 0) {
                TextView sep = new TextView(this);
                sep.setText("›");
                sep.setTextSize(14);
                sep.setTextColor(getThemeColor(android.R.attr.textColorTertiary));
                sep.setPadding(sepPad, vPad, sepPad, vPad);
                llBreadcrumb.addView(sep);
            }

            TextView seg = new TextView(this);
            seg.setText(labels.get(i) == null
                    ? getString(R.string.etp_browse_root) : labels.get(i));
            seg.setTextSize(14);
            seg.setSingleLine(true);
            seg.setPadding(0, vPad, 0, vPad);
            if (isLast) {
                seg.setTextColor(getThemeColor(com.google.android.material.R.attr.colorPrimary));
                seg.setTypeface(null, Typeface.BOLD);
            } else {
                seg.setTextColor(getThemeColor(android.R.attr.textColorSecondary));
                if (selBg != 0) {
                    seg.setBackgroundResource(selBg);
                }
                seg.setClickable(true);
                final int segment = i - 1; // -1 = 此电脑
                seg.setOnClickListener(v -> {
                    if (segment < 0) {
                        browseViewModel.openRoot();
                    } else {
                        browseViewModel.openSegment(segment);
                    }
                });
            }
            llBreadcrumb.addView(seg);
        }

        layoutBreadcrumb.post(() -> layoutBreadcrumb.fullScroll(View.FOCUS_RIGHT));
    }

    // ==================== 分类筛选 / 本地搜索 / 排序 ====================

    /** 根据 分类选中 + 搜索词 + 排序 重算列表与 chips。 */
    private void rebuildView() {
        if (browseMode || fullCategories == null) return;
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
            fabCategories.setVisibility(View.GONE);
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

    /** 渲染分类筛选浮动按钮：点击弹出多选菜单。 */
    private void buildCategoryChips(List<EtpExplorerViewModel.CategoryResult> categories) {
        fabCategories.setVisibility(View.VISIBLE);
        fabCategories.setOnClickListener(v -> {
            PopupMenu popup = new PopupMenu(this, v);
            for (int i = 0; i < categories.size(); i++) {
                EtpExplorerViewModel.CategoryResult c = categories.get(i);
                MenuItem item = popup.getMenu().add(Menu.NONE, i, i, c.label + " (" + c.items.size() + ")");
                item.setCheckable(true);
                item.setChecked(selectedCategories.contains(c.label));
            }
            // 添加"全部"选项
            MenuItem allItem = popup.getMenu().add(Menu.NONE, -1, categories.size(), "全部");
            allItem.setCheckable(true);
            allItem.setChecked(selectedCategories.isEmpty());

            popup.setOnMenuItemClickListener(item -> {
                int idx = item.getItemId();
                if (idx == -1) {
                    // 全部
                    selectedCategories.clear();
                } else if (idx >= 0 && idx < categories.size()) {
                    String label = categories.get(idx).label;
                    if (selectedCategories.contains(label)) {
                        selectedCategories.remove(label);
                    } else {
                        selectedCategories.add(label);
                    }
                }
                rebuildView();
                return true;
            });
            popup.show();
        });
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
        intent.putExtra(EtpSearchActivity.EXTRA_DEVICE_ID, deviceId);
        intent.putExtra(EtpSearchActivity.EXTRA_PORT, devicePort);
        startActivity(intent);
    }

    // ==================== 文件属性对话框 ====================

    /** 长按文件：完整属性 + 操作对话框。 */
    private void showFilePropertiesDialog(EtpClient.EtpSearchResult r) {
        EtpFileDialog.show(this, r, deviceId, deviceHost, devicePort,
                () -> enterBrowseAt(r.path));
    }

    private int getThemeColor(int attr) {
        TypedValue tv = new TypedValue();
        getTheme().resolveAttribute(attr, tv, true);
        return getColor(tv.resourceId);
    }
}
