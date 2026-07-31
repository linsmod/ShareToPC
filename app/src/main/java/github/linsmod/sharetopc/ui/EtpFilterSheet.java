package github.linsmod.sharetopc.ui;

import android.app.Dialog;
import android.os.Bundle;
import android.graphics.drawable.Drawable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ImageSpan;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.DrawableCompat;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.gson.Gson;

import github.linsmod.sharetopc.App;
import github.linsmod.sharetopc.R;

/**
 * ETP 搜索条件 BottomSheet — 在当前搜索页直接调整筛选条件并立即生效。
 * 替代跳转 EtpSettingsActivity 的割裂体验。
 */
public class EtpFilterSheet extends BottomSheetDialogFragment {

    private static final String PREF_KEY = "etp_path_presets";
    private static final String BUILTIN_NAMES[] = {"desktop", "documents", "downloads",
            "videos", "pictures", "music"};
    private static final int BUILTIN_LABELS[] = {
            R.string.path_preset_desktop, R.string.path_preset_documents,
            R.string.path_preset_downloads, R.string.path_preset_videos,
            R.string.path_preset_pictures, R.string.path_preset_music};
    private static final String DRIVES[] = {"C:", "D:", "E:", "F:"};
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

    private SortItem[] sortItems;

    private EtpSettingsActivity.PathPresets presets;
    private final Gson gson = new Gson();
    // 内存级当前激活的排序字段（不为 null 时即当前排序生效）
    private String currentSort;

    // 路径 Chips
    private ChipGroup cgBuiltinPaths;
    private ChipGroup cgDrives;
    // 匹配
    private MaterialSwitch swMatchCase, swWholeWord, swMatchPath, swRegex;
    // 排序
    private TextView tvSortName, tvSortSize, tvSortPath,
            tvSortExt, tvSortDateMod, tvSortDateCre;

    private Runnable onFilterApplied;

    /** 设置筛选应用后的回调 */
    public void setOnFilterApplied(Runnable callback) {
        this.onFilterApplied = callback;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        BottomSheetDialog dialog = (BottomSheetDialog) super.onCreateDialog(savedInstanceState);
        dialog.setOnShowListener(d -> {
            View bottomSheet = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (bottomSheet != null) {
                BottomSheetBehavior.from(bottomSheet).setState(BottomSheetBehavior.STATE_EXPANDED);
            }
        });
        return dialog;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.bottom_sheet_etp_filter, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        cgBuiltinPaths = view.findViewById(R.id.cgBuiltinPaths);
        cgDrives = view.findViewById(R.id.cgDrives);
        swMatchCase = view.findViewById(R.id.swMatchCase);
        swWholeWord = view.findViewById(R.id.swWholeWord);
        swMatchPath = view.findViewById(R.id.swMatchPath);
        swRegex = view.findViewById(R.id.swRegex);
        tvSortName = view.findViewById(R.id.tvSortName);
        tvSortSize = view.findViewById(R.id.tvSortSize);
        tvSortPath = view.findViewById(R.id.tvSortPath);
        tvSortExt = view.findViewById(R.id.tvSortExt);
        tvSortDateMod = view.findViewById(R.id.tvSortDateMod);
        tvSortDateCre = view.findViewById(R.id.tvSortDateCre);

        loadPresets();
        buildBuiltinChips();
        buildDriveChips();
        initSortItems();
        applyToUI();

        view.findViewById(R.id.btnApply).setOnClickListener(v -> {
            collectFromUI();
            savePresets();
            if (onFilterApplied != null) onFilterApplied.run();
            dismiss();
        });
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

    private void buildBuiltinChips() {
        cgBuiltinPaths.removeAllViews();
        for (int i = 0; i < BUILTIN_NAMES.length; i++) {
            String key = BUILTIN_NAMES[i];
            boolean checked = presets.builtin.getOrDefault(key, false);
            Chip chip = createFilterChip(getString(BUILTIN_LABELS[i]), checked);
            chip.setOnCheckedChangeListener((c, isChecked) ->
                    presets.builtin.put(key, isChecked));
            cgBuiltinPaths.addView(chip);
        }
    }

    private void buildDriveChips() {
        cgDrives.removeAllViews();
        for (String drive : DRIVES) {
            boolean checked = presets.drives.getOrDefault(drive, false);
            Chip chip = createFilterChip(drive, checked);
            chip.setOnCheckedChangeListener((c, isChecked) ->
                    presets.drives.put(drive, isChecked));
            cgDrives.addView(chip);
        }
    }

    private Chip createFilterChip(String text, boolean checked) {
        Chip chip = new Chip(requireContext());
        chip.setText(text);
        chip.setCheckable(true);
        chip.setChecked(checked);
        chip.setChipIconResource(android.R.drawable.ic_menu_compass);
        return chip;
    }

    private void initSortItems() {
        TextView[] views = {tvSortName, tvSortSize, tvSortPath,
                tvSortExt, tvSortDateMod, tvSortDateCre};

        // 旧数据迁移: 如果 sortFields 为空但 sortName 有值，从旧字段恢复
        if (presets.sortFields.isEmpty() && presets.sortName != null) {
            presets.sortFields.put(presets.sortName, presets.sortAsc ? 1 : 2);
        }
        // 补齐可能缺失的字段
        for (String name : SORT_NAMES) {
            if (!presets.sortFields.containsKey(name)) {
                presets.sortFields.put(name, 0);
            }
        }

        sortItems = new SortItem[SORT_NAMES.length];
        for (int i = 0; i < SORT_NAMES.length; i++) {
            String name = SORT_NAMES[i];
            SortItem item = new SortItem(views[i], name);
            item.state = presets.sortFields.getOrDefault(name, 0);
            sortItems[i] = item;
            final int idx = i;
            views[i].setOnClickListener(v -> onSortItemClicked(idx));
        }
        // 恢复 currentSort
        currentSort = (presets.sortName != null && !presets.sortName.isEmpty()
                && presets.sortFields.getOrDefault(presets.sortName, 0) != 0)
                ? presets.sortName : null;
    }

    /** 每个字段独立循环: none → asc → desc → none */
    private void onSortItemClicked(int index) {
        SortItem clicked = sortItems[index];
        clicked.state = (clicked.state + 1) % 3; // 0→1→2→0
        presets.sortFields.put(clicked.sortName, clicked.state);

        // 更新内存级 currentSort: 以最后切到非 none 的字段为准
        if (clicked.state != 0) {
            currentSort = clicked.sortName;
        } else if (clicked.sortName.equals(currentSort)) {
            // 当前激活字段被切到 none，找其他非 none 字段，否则清空
            currentSort = null;
            for (SortItem item : sortItems) {
                if (item.state != 0) { currentSort = item.sortName; break; }
            }
        }

        // 同步到 presets 用于实际查询
        if (currentSort != null) {
            presets.sortName = currentSort;
            int state = presets.sortFields.getOrDefault(currentSort, 0);
            presets.sortAsc = (state == 1);
        } else {
            presets.sortName = null;
            presets.sortAsc = true;
        }
        refreshSortIndicators();
    }

    private void refreshSortIndicators() {
        TypedValue tv = new TypedValue();
        requireContext().getTheme().resolveAttribute(
                com.google.android.material.R.attr.colorPrimary, tv, true);
        int accent = tv.data;
        tv = new TypedValue();
        requireContext().getTheme().resolveAttribute(
                com.google.android.material.R.attr.colorOnSurface, tv, true);
        int subtle = (tv.data & 0x00FFFFFF) | 0x60000000; // 37% alpha

        float textHeight = tvSortName.getPaint().getFontMetrics().descent
                - tvSortName.getPaint().getFontMetrics().ascent;
        int iconSize = (int) (textHeight * 0.6f);

        for (SortItem item : sortItems) {
            int resId;
            int color;
            if (item.state == 1) { resId = R.drawable.ic_sort_asc; color = accent; }
            else if (item.state == 2) { resId = R.drawable.ic_sort_desc; color = accent; }
            else { resId = R.drawable.ic_sort_both; color = subtle; }

            Drawable icon = makeIcon(resId, color, iconSize);
            SpannableString ss = new SpannableString(item.label + "  ");
            ImageSpan span = new ImageSpan(icon, ImageSpan.ALIGN_CENTER);
            ss.setSpan(span, ss.length() - 1, ss.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            item.view.setText(ss);
        }
    }

    /** 加载 drawable、着色并设置 bounds */
    private Drawable makeIcon(int resId, int color, int size) {
        Drawable d = ContextCompat.getDrawable(requireContext(), resId);
        if (d == null) return null;
        d = DrawableCompat.wrap(d).mutate();
        DrawableCompat.setTint(d, color);
        d.setBounds(0, 0, size, size);
        return d;
    }

    private void applyToUI() {
        swMatchCase.setChecked(presets.matchCase != 0);
        swWholeWord.setChecked(presets.matchWholeWord != 0);
        swMatchPath.setChecked(presets.matchPath != 0);
        swRegex.setChecked(presets.matchRegex != 0);
        refreshSortIndicators();
    }

    private void collectFromUI() {
        presets.matchCase = swMatchCase.isChecked() ? 1 : 0;
        presets.matchWholeWord = swWholeWord.isChecked() ? 1 : 0;
        presets.matchPath = swMatchPath.isChecked() ? 1 : 0;
        presets.matchRegex = swRegex.isChecked() ? 1 : 0;
        // sortFields 已在点击时同步到 presets
        // sortName/sortAsc 已在 onSortItemClicked 中通过 currentSort 同步
    }
}
