package github.linsmod.sharetopc.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ImageSpan;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.DrawableCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.divider.MaterialDividerItemDecoration;
import com.google.android.material.textfield.TextInputEditText;
import com.google.gson.Gson;

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
 * 筛选条件直接内联在搜索框下方。
 */
public class EtpSearchActivity extends androidx.appcompat.app.AppCompatActivity {

    public static final String EXTRA_DEVICE_NAME = "device_name";
    public static final String EXTRA_HOST = "host";

    private static final String PREF_KEY = "etp_path_presets";
    private static final String[] SORT_NAMES = {
            "name", "size", "path", "extension", "date_modified", "date_created"};

    private EtpSearchViewModel viewModel;

    // View 引用
    private View searchArea;
    private TextInputEditText etSearch;
    private View layoutHistory;
    private ChipGroup cgHistory;
    private View layoutProgressSearch;
    private RecyclerView rvResults;
    private View layoutResultHeader, dividerResult;
    private TextView tvResultCount;
    private MaterialButton btnToggleDetails;
    private View layoutQueryPaths;
    private LinearLayout layoutPathCheckboxes;
    private MaterialButton btnChangeSettings;
    private View layoutQueryParams;
    private LinearLayout layoutQueryParamRows;
    private View layoutEmptyScroll;
    private View layoutResultsFrame;

    // 快速检索（设置页定义，点击即搜索）
    private HorizontalScrollView layoutQuickSearch;
    private ChipGroup cgQuickSearch;

    private boolean showingResults;
    /** 是否正在查询（Searching 状态）：返回时撤销查询并提示。 */
    private boolean querying;

    // 结果排序
    private View layoutResultSort;
    private ChipGroup cgMatchOptions;
    private Chip chipMatchCase, chipWholeWord, chipMatchPath, chipRegex;
    private TextView tvSortName, tvSortSize, tvSortPath,
            tvSortExt, tvSortDateMod, tvSortDateCre;

    // 搜索选项
    private MaterialCheckBox cbExcludeList;

    private EtpSettingsActivity.PathPresets presets;
    private SortItem[] sortItems;
    private String currentSort;
    private final Gson gson = new Gson();

    private EtpResultAdapter resultAdapter;

    /** 打开设置页前记录的查询参数快照（规范化 JSON），返回时对比是否变化。 */
    private String settingsQuerySnapshot;

    private final ActivityResultLauncher<Intent> settingsLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        // 从设置页返回：重新加载预设；生成的查询参数无变化时不发起新查询
                        String before = settingsQuerySnapshot;
                        loadPresets();
                        initSortItems();
                        applyToUI();
                        if (before != null && before.equals(queryParamsSnapshot())) return;
                        String q = etSearch.getText().toString().trim();
                        if (!q.isEmpty()) viewModel.search(q);
                    });

    /** 打开设置页：先记录当前查询参数快照，返回时用于检测是否变化。 */
    private void openSettings() {
        settingsQuerySnapshot = queryParamsSnapshot();
        settingsLauncher.launch(new Intent(this, EtpSettingsActivity.class));
    }

    /**
     * 生成查询参数快照（规范化 JSON）：仅包含实际参与查询的参数
     * （勾选路径、结果排除、匹配选项、排序、结果上限）。
     * 与整体预设不同，快速检索、显示排序字段等纯 UI 配置变化不会触发重新查询。
     */
    private String queryParamsSnapshot() {
        EtpSettingsActivity.PathPresets p = presets;
        java.util.LinkedHashMap<String, Object> snap = new java.util.LinkedHashMap<>();

        // 勾选路径：trim 后按顺序收集（与 buildPathFilter 拼接行为一致）
        List<String> checkedPaths = new java.util.ArrayList<>();
        if (p.paths != null) {
            for (EtpSettingsActivity.PathEntry e : p.paths) {
                if (e.checked) checkedPaths.add(e.path.trim());
            }
        }
        snap.put("paths", checkedPaths);

        // 结果排除：开关 + 排除表达式（与 buildPathFilter 附加 ! 的列表一致）
        snap.put("excludeEnabled", p.excludeEnabled);
        snap.put("excludes", EtpSettingsActivity.PathPresets.parseExcludes(p.excludeText));

        // 匹配选项
        snap.put("matchCase", p.matchCase);
        snap.put("matchWholeWord", p.matchWholeWord);
        snap.put("matchPath", p.matchPath);
        snap.put("matchRegex", p.matchRegex);

        // 排序（无排序字段时归一化为空串）
        snap.put("sortName", p.sortName == null ? "" : p.sortName);
        snap.put("sortAsc", p.sortAsc);

        // 结果上限
        snap.put("maxResults", Math.max(0, p.maxResults));
        return gson.toJson(snap);
    }

    // ---- SortItem ----

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
        initFilter();
        initEnterSearch();
        initViewModel(deviceHost, etpPort);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        viewModel.removeObserver();
    }

    @Override
    public void onBackPressed() {
        if (showingResults) {
            backToSearch();
        } else {
            super.onBackPressed();
        }
    }

    /** 从结果页回到搜索页；正在查询时先撤销查询并提示已取消。 */
    private void backToSearch() {
        if (querying) {
            viewModel.cancelQuery();
            Toast.makeText(this, R.string.etp_query_cancelled, Toast.LENGTH_SHORT).show();
        }
        showingResults = false;
        renderResult(null); // 触发 renderReady
        etSearch.requestFocus();
    }

    // ==================== 初始化 ====================

    private void initViews(String deviceName) {
        androidx.appcompat.widget.Toolbar toolbar = findViewById(R.id.toolbarEtpSearch);
        toolbar.setTitle(getString(R.string.etp_search_title_format,
                deviceName != null ? deviceName : ""));
        toolbar.setNavigationOnClickListener(v -> {
            if (showingResults) {
                backToSearch();
            } else {
                finish();
            }
        });
        toolbar.inflateMenu(R.menu.etp_search_menu);
        toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.action_etp_settings) {
                openSettings();
                return true;
            }
            return false;
        });

        etSearch = findViewById(R.id.etSearch);
        searchArea = findViewById(R.id.searchArea);
        cgHistory = findViewById(R.id.cgRecentSearches);
        layoutHistory = findViewById(R.id.layoutHistory);

        // 清空搜索历史
        findViewById(R.id.tvClearHistory).setOnClickListener(v -> {
            viewModel.clearHistory();
            cgHistory.removeAllViews();
            layoutHistory.setVisibility(View.GONE);
        });
        layoutProgressSearch = findViewById(R.id.layoutProgressSearch);
        rvResults = findViewById(R.id.rvResults);
        tvResultCount = findViewById(R.id.tvResultCount);
        btnToggleDetails = findViewById(R.id.btnToggleDetails);
        btnToggleDetails.setOnClickListener(v -> {
            boolean show = layoutEmptyScroll.getVisibility() != View.VISIBLE;
            layoutEmptyScroll.setVisibility(show ? View.VISIBLE : View.GONE);
            btnToggleDetails.setText(show
                    ? R.string.etp_details_hide : R.string.etp_details_show);
        });
        layoutResultHeader = findViewById(R.id.layoutResultHeader);
        dividerResult = findViewById(R.id.dividerResult);
        layoutQueryPaths = findViewById(R.id.layoutQueryPaths);
        layoutPathCheckboxes = findViewById(R.id.layoutPathCheckboxes);
        btnChangeSettings = findViewById(R.id.btnChangeSettings);
        btnChangeSettings.setOnClickListener(v -> openSettings());
        layoutQueryParams = findViewById(R.id.layoutQueryParams);
        layoutQueryParamRows = findViewById(R.id.layoutQueryParamRows);
        layoutEmptyScroll = findViewById(R.id.layoutEmptyScroll);
        layoutResultsFrame = findViewById(R.id.layoutResultsFrame);
        layoutQuickSearch = findViewById(R.id.layoutQuickSearch);
        cgQuickSearch = findViewById(R.id.cgQuickSearch);

        // 结果排序视图
        layoutResultSort = findViewById(R.id.layoutResultSort);
        cgMatchOptions = findViewById(R.id.cgMatchOptions);
        cbExcludeList = findViewById(R.id.cbExcludeList);
        tvSortName = findViewById(R.id.tvSortName);
        tvSortSize = findViewById(R.id.tvSortSize);
        tvSortPath = findViewById(R.id.tvSortPath);
        tvSortExt = findViewById(R.id.tvSortExt);
        tvSortDateMod = findViewById(R.id.tvSortDateMod);
        tvSortDateCre = findViewById(R.id.tvSortDateCre);

        rvResults.setLayoutManager(new LinearLayoutManager(this));
        rvResults.addItemDecoration(new MaterialDividerItemDecoration(this,
                MaterialDividerItemDecoration.VERTICAL));
        rvResults.setItemAnimator(null);

        resultAdapter = new EtpResultAdapter(this::showFilePropertiesDialog);
        // 列表底部“加载更多” → ViewModel 拉取下一批
        resultAdapter.setOnLoadMoreListener(() -> viewModel.loadMore());
        rvResults.setAdapter(resultAdapter);

        // 键盘搜索键（唯一记录历史的入口）
        etSearch.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                String q = v.getText() != null ? v.getText().toString().trim() : "";
                if (!q.isEmpty()) {
                    viewModel.addSearchHistory(q);
                    viewModel.search(q);
                }
                return true;
            }
            return false;
        });

        // 激活输入框时显示常用位置和搜索历史，失焦时隐藏
        etSearch.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus && viewModel.isConnected()) {
                refreshHistoryChips();
            } else if (!hasFocus) {
                layoutHistory.setVisibility(View.GONE);
            }
        });

        // 结果页点击搜索框 → 返回搜索
        etSearch.setOnClickListener(v -> {
            if (showingResults) {
                backToSearch();
            }
        });

    }

    // ==================== 筛选（内联） ====================

    private void initFilter() {
        loadPresets();
        buildMatchChips();
        initSortItems();
        applyToUI();
        initExcludeSwitch();
    }

    /** 搜索选项：查询时附加排除列表开关（先 applyToUI 同步状态，再注册监听避免误触发）。 */
    private void initExcludeSwitch() {
        cbExcludeList.setOnCheckedChangeListener((b, checked) -> {
            if (presets.excludeEnabled == checked) return; // 状态同步触发，忽略
            presets.excludeEnabled = checked;
            commitFilter();
        });
    }

    private void buildMatchChips() {
        chipMatchCase = createMatchChip("Aa", presets.matchCase != 0,
                checked -> { presets.matchCase = checked ? 1 : 0; commitFilter(); });
        chipWholeWord = createMatchChip("Ab|", presets.matchWholeWord != 0,
                checked -> { presets.matchWholeWord = checked ? 1 : 0; commitFilter(); });
        chipMatchPath = createMatchChip("路径", presets.matchPath != 0,
                checked -> { presets.matchPath = checked ? 1 : 0; commitFilter(); });
        chipRegex = createMatchChip(".*", presets.matchRegex != 0,
                checked -> { presets.matchRegex = checked ? 1 : 0; commitFilter(); });

        cgMatchOptions.removeAllViews();
        cgMatchOptions.addView(chipMatchCase);
        cgMatchOptions.addView(chipWholeWord);
        cgMatchOptions.addView(chipMatchPath);
        cgMatchOptions.addView(chipRegex);
    }

    private Chip createMatchChip(String text, boolean checked,
                                  androidx.core.util.Consumer<Boolean> listener) {
        Chip chip = new Chip(this, null, com.google.android.material.R.attr.chipStyle);
        chip.setText(text);
        chip.setCheckable(true);
        chip.setChecked(checked);
        chip.setOnCheckedChangeListener((c, isChecked) -> listener.accept(isChecked));
        return chip;
    }

    private void commitFilter() {
        savePresets();
        viewModel.onFilterChanged();
    }

    private void loadPresets() {
        String json = App.get().settingsStore.getString(PREF_KEY, null);
        if (json != null) {
            try {
                presets = gson.fromJson(json, EtpSettingsActivity.PathPresets.class);
                if (presets != null) return;
            } catch (Exception ignored) {}
        }
        presets = EtpSettingsActivity.PathPresets.defaults();
    }

    private void savePresets() {
        App.get().settingsStore.putString(PREF_KEY, gson.toJson(presets));
    }

    private void initSortItems() {
        if (presets.sortFields.isEmpty() && presets.sortName != null) {
            presets.sortFields.put(presets.sortName, presets.sortAsc ? 1 : 2);
        }
        for (String name : SORT_NAMES) {
            if (!presets.sortFields.containsKey(name)) {
                presets.sortFields.put(name, 0);
            }
        }

        // 旧配置兼容：uiSortFields 为空时默认显示 4 个字段
        if (presets.uiSortFields == null || presets.uiSortFields.isEmpty()) {
            presets.uiSortFields = new java.util.ArrayList<>();
            presets.uiSortFields.add("name");
            presets.uiSortFields.add("size");
            presets.uiSortFields.add("path");
            presets.uiSortFields.add("date_modified");
        }

        // 按 SORT_NAMES 顺序映射视图，未勾选字段隐藏
        TextView[] allViews = {tvSortName, tvSortSize, tvSortPath,
                tvSortExt, tvSortDateMod, tvSortDateCre};
        java.util.List<SortItem> items = new java.util.ArrayList<>();
        for (int i = 0; i < SORT_NAMES.length; i++) {
            String name = SORT_NAMES[i];
            TextView view = allViews[i];
            if (!presets.uiSortFields.contains(name)) {
                view.setVisibility(View.GONE);
                continue;
            }
            view.setVisibility(View.VISIBLE);
            SortItem item = new SortItem(view, name);
            item.state = presets.sortFields.getOrDefault(name, 0);
            items.add(item);
            final int idx = items.size() - 1;
            view.setOnClickListener(v -> onSortItemClicked(idx));
        }
        sortItems = items.toArray(new SortItem[0]);
        currentSort = (presets.sortName != null && !presets.sortName.isEmpty()
                && presets.sortFields.getOrDefault(presets.sortName, 0) != 0)
                ? presets.sortName : null;
    }

    private void onSortItemClicked(int index) {
        SortItem clicked = sortItems[index];
        if (clicked.state == 0) {
            // 单选：取消其他项激活，激活当前项并沿用设置页方向
            for (SortItem item : sortItems) {
                if (item != clicked && item.state != 0) {
                    item.state = 0;
                    presets.sortFields.put(item.sortName, 0);
                }
            }
            clicked.state = presets.sortAsc ? 1 : 2;
            presets.sortFields.put(clicked.sortName, clicked.state);
            currentSort = clicked.sortName;
        } else {
            // 已激活：切换升/降序
            clicked.state = clicked.state == 1 ? 2 : 1;
            presets.sortFields.put(clicked.sortName, clicked.state);
        }

        presets.sortName = currentSort;
        presets.sortAsc = presets.sortFields.getOrDefault(currentSort, 0) == 1;
        refreshSortIndicators();
        commitFilter();
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
        float textHeight = tvSortName.getPaint().getFontMetrics().descent
                - tvSortName.getPaint().getFontMetrics().ascent;
        int iconSize = (int) (textHeight * 0.6f);

        for (SortItem item : sortItems) {
            boolean active = item.state != 0;
            int resId;
            int iconColor;
            if (item.state == 1) {
                resId = R.drawable.ic_sort_asc; iconColor = accent;
            } else if (item.state == 2) {
                resId = R.drawable.ic_sort_desc; iconColor = accent;
            } else {
                resId = R.drawable.ic_sort_both; iconColor = subtle;
            }

            // 文字颜色：激活项用主色加粗，未激活用半透明常规
            item.view.setTextColor(active ? accent : subtle);
            item.view.setTypeface(null, active ? Typeface.BOLD : Typeface.NORMAL);

            // 简化样式：无背景、仅保留垂直 padding
            item.view.setBackground(null);
            int vPad = (int) (density * 2);
            item.view.setPadding(0, vPad, 0, vPad);

            Drawable icon = makeIcon(resId, iconColor, iconSize);
            SpannableString ss = new SpannableString(item.label + "  ");
            ImageSpan span = new ImageSpan(icon, ImageSpan.ALIGN_CENTER);
            ss.setSpan(span, ss.length() - 1, ss.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
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

    private void applyToUI() {
        if (chipMatchCase != null) chipMatchCase.setChecked(presets.matchCase != 0);
        if (chipWholeWord != null) chipWholeWord.setChecked(presets.matchWholeWord != 0);
        if (chipMatchPath != null) chipMatchPath.setChecked(presets.matchPath != 0);
        if (chipRegex != null) chipRegex.setChecked(presets.matchRegex != 0);
        if (cbExcludeList != null) cbExcludeList.setChecked(presets.excludeEnabled);
        refreshSortIndicators();
        refreshQuickSearches();
    }

    /** 渲染快速检索 chips：设置页每行"显示名 (过滤模式)"，点击后括号内模式直接作为 kw 搜索。 */
    private void refreshQuickSearches() {
        List<String[]> items =
                EtpSettingsActivity.PathPresets.parseQuickSearches(presets.quickSearchText);
        if (items.isEmpty()) {
            layoutQuickSearch.setVisibility(View.GONE);
            return;
        }
        layoutQuickSearch.setVisibility(View.VISIBLE);
        cgQuickSearch.removeAllViews();
        for (String[] item : items) {
            Chip chip = new Chip(this, null, com.google.android.material.R.attr.chipStyle);
            chip.setText(item[0]);
            chip.setClickable(true);
            chip.setCheckable(false);
            chip.setOnClickListener(v -> {
                // 与搜索历史一致：模式直接填入搜索框并作为 kw 发起搜索
                etSearch.setText(item[1]);
                viewModel.search(item[1]);
            });
            cgQuickSearch.addView(chip);
        }
    }

    private void collectFromUI() {
        if (chipMatchCase != null) presets.matchCase = chipMatchCase.isChecked() ? 1 : 0;
        if (chipWholeWord != null) presets.matchWholeWord = chipWholeWord.isChecked() ? 1 : 0;
        if (chipMatchPath != null) presets.matchPath = chipMatchPath.isChecked() ? 1 : 0;
        if (chipRegex != null) presets.matchRegex = chipRegex.isChecked() ? 1 : 0;
    }

    // ==================== 搜索触发（仅回车） ====================

    private void initEnterSearch() {
        // 清空搜索框 → 回到 Ready 态
        etSearch.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(android.text.Editable s) {
                if (s.toString().trim().isEmpty()) {
                    renderResult(null);
                    etSearch.requestFocus();
                }
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
            } else if (state instanceof SearchUiState.LoadMoreState) {
                // 加载更多进行中/失败：只更新列表底部 footer（不改变结果页）
                SearchUiState.LoadMoreState lm = (SearchUiState.LoadMoreState) state;
                resultAdapter.setFooterState(lm.loading
                                ? EtpResultAdapter.FOOTER_LOADING
                                : EtpResultAdapter.FOOTER_ERROR,
                        0);
            } else if (state instanceof SearchUiState.Empty) {
                SearchUiState.Empty e = (SearchUiState.Empty) state;
                renderEmpty(e.query);
            } else if (state instanceof SearchUiState.Error) {
                renderError(((SearchUiState.Error) state).message);
            }
        });

        // 仅保存目标地址；首次发起搜索时才建立连接
        viewModel.init(host, port);
    }

    // ==================== 状态渲染 ====================

    /** 显示进度条：容器高度与结果条数行一致，查询切换时不抖动。 */
    private void showProgress() {
        Paint.FontMetrics fm = tvResultCount.getPaint().getFontMetrics();
        float density = getResources().getDisplayMetrics().density;
        // 结果头部高度 = 文字行高 + paddingTop 10dp + paddingBottom 6dp
        int h = Math.round(fm.descent - fm.ascent + 16 * density);
        ViewGroup.LayoutParams lp = layoutProgressSearch.getLayoutParams();
        if (lp.height != h) {
            lp.height = h;
            layoutProgressSearch.requestLayout();
        }
        layoutProgressSearch.setVisibility(View.VISIBLE);
    }

    private void renderConnecting() {
        showingResults = false;
        querying = false;
        setSearchEditable(true);
        searchArea.setVisibility(View.VISIBLE);
        showProgress();
        rvResults.setVisibility(View.GONE);
        layoutResultHeader.setVisibility(View.GONE);
        dividerResult.setVisibility(View.GONE);
        tvResultCount.setVisibility(View.GONE);
        cgMatchOptions.setVisibility(View.GONE);
        layoutHistory.setVisibility(View.GONE);
        layoutResultSort.setVisibility(View.GONE);
        layoutEmptyScroll.setVisibility(View.GONE);
        layoutResultsFrame.setVisibility(View.GONE);
    }

    private void renderReady() {
        showingResults = false;
        querying = false;
        setSearchEditable(true);
        searchArea.setVisibility(View.VISIBLE);
        cgMatchOptions.setVisibility(View.GONE);
        layoutProgressSearch.setVisibility(View.GONE);
        rvResults.setVisibility(View.GONE);
        resultAdapter.setFooterState(EtpResultAdapter.FOOTER_HIDDEN, 0); // 重置 footer
        layoutResultHeader.setVisibility(View.GONE);
        dividerResult.setVisibility(View.GONE);
        tvResultCount.setVisibility(View.GONE);
        layoutResultSort.setVisibility(View.GONE);
        layoutEmptyScroll.setVisibility(View.GONE);
        layoutResultsFrame.setVisibility(View.GONE);
        etSearch.requestFocus();
    }

    private void renderSearching(String query) {
        showingResults = true;
        querying = true;
        setSearchEditable(false);
        searchArea.setVisibility(View.GONE);
        cgMatchOptions.setVisibility(View.VISIBLE);
        showProgress();
        rvResults.setVisibility(View.GONE);
        layoutResultHeader.setVisibility(View.GONE);
        dividerResult.setVisibility(View.VISIBLE);
        layoutEmptyScroll.setVisibility(View.GONE);
        layoutResultsFrame.setVisibility(View.GONE);
        layoutResultSort.setVisibility(View.VISIBLE);
    }

    private void renderResult(SearchUiState.HasResults r) {
        showingResults = true;
        querying = false;
        setSearchEditable(false);
        searchArea.setVisibility(View.GONE);
        cgMatchOptions.setVisibility(View.VISIBLE);
        layoutProgressSearch.setVisibility(View.GONE);
        rvResults.setVisibility(View.VISIBLE);
        layoutResultHeader.setVisibility(View.VISIBLE);
        dividerResult.setVisibility(View.VISIBLE);
        layoutEmptyScroll.setVisibility(View.GONE);
        layoutResultsFrame.setVisibility(View.VISIBLE);
        layoutResultSort.setVisibility(View.VISIBLE);

        if (r == null) {
            resultAdapter.submitList(null);
            renderReady();
            return;
        }

        if (r.items.isEmpty()) {
            renderEmpty(r.query);
            return;
        }

        tvResultCount.setVisibility(View.VISIBLE);
        tvResultCount.setText(getString(R.string.etp_result_count, r.totalCount));
        btnToggleDetails.setVisibility(View.VISIBLE);
        btnToggleDetails.setText(R.string.etp_details_show);
        resultAdapter.submitList(r.items);
        // footer：还有未传输的结果→“加载更多”；否则常驻“已加载全部 N 条”不消失
        resultAdapter.setFooterState(r.hasMore
                        ? EtpResultAdapter.FOOTER_LOAD_MORE
                        : EtpResultAdapter.FOOTER_ALL_LOADED,
                r.items.size());
        // 详情区内容总是渲染（默认收起，点击“详情”展开）
        renderQueryPaths();
        renderQueryParams(r.query);
    }

    private void renderEmpty(String query) {
        showingResults = true;
        querying = false;
        setSearchEditable(false);
        searchArea.setVisibility(View.GONE);
        cgMatchOptions.setVisibility(View.VISIBLE);
        layoutProgressSearch.setVisibility(View.GONE);
        rvResults.setVisibility(View.GONE);
        layoutResultHeader.setVisibility(View.VISIBLE);
        dividerResult.setVisibility(View.VISIBLE);
        layoutResultSort.setVisibility(View.GONE);
        tvResultCount.setVisibility(View.VISIBLE);
        tvResultCount.setText(getString(R.string.etp_result_count, 0));
        btnToggleDetails.setVisibility(View.VISIBLE);
        btnToggleDetails.setText(R.string.etp_details_hide);
        resultAdapter.submitList(null);
        resultAdapter.setFooterState(EtpResultAdapter.FOOTER_HIDDEN, 0); // 无结果不显示 footer
        layoutResultsFrame.setVisibility(View.GONE);
        layoutEmptyScroll.setVisibility(View.VISIBLE);

        // 渲染已选择的搜索位置与查询语句详情
        renderQueryPaths();
        renderQueryParams(query);
    }

    /** 渲染空结果时的"查询了以下位置"：仅显示已勾选的路径，平铺展示。 */
    private void renderQueryPaths() {
        layoutPathCheckboxes.removeAllViews();
        List<EtpSearchViewModel.PathItem> all = viewModel.getAllPathItems();
        List<EtpSearchViewModel.PathItem> selected = new java.util.ArrayList<>();
        for (EtpSearchViewModel.PathItem item : all) {
            if (item.checked) selected.add(item);
        }
        if (selected.isEmpty()) {
            layoutQueryPaths.setVisibility(View.GONE);
            btnChangeSettings.setVisibility(View.GONE);
            return;
        }

        // 直接平铺所有已选位置，不按类型分组
        for (EtpSearchViewModel.PathItem item : selected) {
            TextView tv = new TextView(this);
            tv.setText(item.label);
            tv.setTextColor(getThemeColor(android.R.attr.textColorPrimary));
            tv.setPadding(0, dp(2), 0, dp(2));
            tv.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            layoutPathCheckboxes.addView(tv);
        }

        // "更改设置"按钮放在 section 标题行
        btnChangeSettings.setVisibility(View.VISIBLE);
        layoutQueryPaths.setVisibility(View.VISIBLE);
    }

    /** 渲染空结果时的"查询参数"小节：仅显示查询语句，直接显示在 section 下方。 */
    private void renderQueryParams(String query) {
        layoutQueryParamRows.removeAllViews();

        TextView tv = new TextView(this);
        // 直接显示查询语句本身，无"实际查询"前缀
        tv.setText(viewModel.buildPathFilter(query));
        tv.setTextColor(getThemeColor(android.R.attr.textColorPrimary));
        tv.setTextIsSelectable(true);
        tv.setPadding(0, dp(2), 0, dp(2));
        tv.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        layoutQueryParamRows.addView(tv);
        layoutQueryParams.setVisibility(View.VISIBLE);
    }

    private void renderError(String message) {
        showingResults = false;
        querying = false;
        setSearchEditable(true);
        searchArea.setVisibility(View.VISIBLE);
        cgMatchOptions.setVisibility(View.GONE);
        layoutProgressSearch.setVisibility(View.GONE);
        layoutResultHeader.setVisibility(View.GONE);
        dividerResult.setVisibility(View.GONE);
        tvResultCount.setVisibility(View.GONE);
        resultAdapter.submitList(null);
        resultAdapter.setFooterState(EtpResultAdapter.FOOTER_HIDDEN, 0); // 重置 footer
        layoutHistory.setVisibility(View.GONE);
        layoutResultSort.setVisibility(View.GONE);
        layoutEmptyScroll.setVisibility(View.GONE);
        layoutResultsFrame.setVisibility(View.GONE);
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    /** 切换搜索框的编辑/只读状态。结果页设为只读，点击可回到搜索页。 */
    private void setSearchEditable(boolean editable) {
        etSearch.setFocusable(editable);
        etSearch.setFocusableInTouchMode(editable);
        etSearch.setCursorVisible(editable);
        etSearch.setClickable(!editable);
    }

    // ==================== 搜索历史 Chips ====================

    private void refreshHistoryChips() {
        cgHistory.removeAllViews();
        List<String> history = viewModel.getHistoryReversed();
        if (history.isEmpty()) {
            layoutHistory.setVisibility(View.GONE);
            return;
        }
        layoutHistory.setVisibility(View.VISIBLE);
        for (int i = 0; i < history.size(); i++) {
            String q = history.get(i);
            Chip chip = new Chip(this, null, com.google.android.material.R.attr.chipStyle);
            chip.setText(q);
            chip.setClickable(true);
            chip.setCheckable(false);
            chip.setOnClickListener(v -> {
                etSearch.setText(q);
                viewModel.search(q);
            });
            // 无删除按钮防误触：长按弹菜单（删除该条），点选即执行；清空走顶部按钮
            chip.setOnLongClickListener(v -> {
                PopupMenu popup = new PopupMenu(this, chip);
                popup.getMenu().add(0, 1, 0, R.string.etp_history_delete_item);
                popup.setOnMenuItemClickListener(item -> {
                    if (item.getItemId() == 1) {
                        viewModel.removeHistory(q);
                        refreshHistoryChips();
                        return true;
                    }
                    return false;
                });
                popup.show();
                return true;
            });
            cgHistory.addView(chip);
        }
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
