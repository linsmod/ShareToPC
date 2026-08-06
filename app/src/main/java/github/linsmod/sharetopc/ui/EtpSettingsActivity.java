package github.linsmod.sharetopc.ui;

import android.content.res.TypedArray;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.chip.Chip;
import com.google.android.material.textfield.TextInputEditText;
import com.google.gson.Gson;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import github.linsmod.sharetopc.App;
import github.linsmod.sharetopc.R;
import github.linsmod.sharetopc.data.SettingsStore;

/**
 * ETP 设置页：路径筛选、端口管理、匹配/排序选项。
 */
public class EtpSettingsActivity extends AppCompatActivity {

    private static final String PREF_KEY = "etp_path_presets";

    static class PathEntry {
        String type;
        String label;
        String path;
        boolean checked;

        PathEntry() {}

        PathEntry(String type, String label, String path) {
            this(type, label, path, true);
        }

        PathEntry(String type, String label, String path, boolean checked) {
            this.type = type;
            this.label = label;
            this.path = path;
            this.checked = checked;
        }
    }

    static class PathPresets {
        List<PathEntry> paths = new ArrayList<>();

        int matchCase;
        int matchPath;
        int matchRegex;
        int matchWholeWord;

        String sortName;
        boolean sortAsc = true;
        Map<String, Integer> sortFields = new LinkedHashMap<>();
        /** 搜索页显示的排序字段（与 SORT_NAMES 顺序一致）。 */
        List<String> uiSortFields = new ArrayList<>();

        int maxResults;
        /** 快速检索：每行一个"显示名 (过滤模式)"，如 "Word 文档 (*.doc | *.docx)"。 */
        String quickSearchText;
        /** 结果排除：每行一个排除表达式，搜索时附加 ! 前缀。 */
        String excludeText;
        /** 搜索页开关：查询时是否附加结果排除列表。 */
        boolean excludeEnabled = true;
        // Search Syntax using paths: pathA | pathB | pathC {kw}
        static PathPresets defaults() {
            PathPresets p = new PathPresets();
            p.paths.add(new PathEntry("common", "桌面", "regex:[A-Z]:\\\\Users\\\\([^\\\\]+)\\\\Desktop\\\\([^\\\\]+)"));
            p.paths.add(new PathEntry("common", "文档目录", "regex:[A-Z]:\\\\Users\\\\([^\\\\]+)\\\\Documents\\\\([^\\\\]+)"));
            p.paths.add(new PathEntry("common", "下载目录", "regex:[A-Z]:\\\\Users\\\\([^\\\\]+)\\\\Downloads\\\\([^\\\\]+)"));
            p.paths.add(new PathEntry("common", "视频目录", "regex:[A-Z]:\\\\Users\\\\([^\\\\]+)\\\\Videos\\\\([^\\\\]+)"));
            p.paths.add(new PathEntry("common", "图片目录", "regex:[A-Z]:\\\\Users\\\\([^\\\\]+)\\\\Pictures\\\\([^\\\\]+)"));
            p.paths.add(new PathEntry("common", "音乐目录", "regex:[A-Z]:\\\\Users\\\\([^\\\\]+)\\\\Musics\\\\([^\\\\]+)"));
            p.paths.add(new PathEntry("common", "Program Files", "path:regex:[A-Z]:\\\\Program\\sFiles",false));
            p.paths.add(new PathEntry("common", "Program Files (x86)", "path:regex:[A-Z]:\\\\Program\\sFiles\\s\\(x86\\)",false));
            for (char c = 'A'; c <= 'Z'; c++) {
                p.paths.add(new PathEntry("drive", c + ": 盘", c + ":\\",false));
            }
            p.paths.add(new PathEntry("custom_path", "D:\\Software", "D:\\Software",false));
            p.maxResults = 200;
            for (String f : new String[]{"name", "size", "path", "extension", "date_modified", "date_created"}) {
                p.sortFields.put(f, 0);
            }
            p.uiSortFields.add("name");
            p.uiSortFields.add("size");
            p.uiSortFields.add("path");
            p.uiSortFields.add("date_modified");
            p.quickSearchText = "Word 文档 (*.doc | *.docx)\n"
                    + "Excel 文件 (ext:xls | ext:xlsx)\n"
                    + "PPT (ext:ppt | ext:pptx)\n"
                    + "PDF 文件 (ext:pdf)\n"
                    + "文本文档(ext:txt | ext:md)\n"
                    + "压缩包 (ext:zip | ext:rar | ext:7z | ext:tar.gz)\n"
                    + "图片 (image:)\n"
                    + "视频 (video: size:>10mb)\n"
                    + "音频 (audio:)\n"
                    + "思维导图 (ext:xmind)\n"
                    + "电脑程序 (ext:exe | ext:msi | ext:msix)\n"
                    + "APK (ext:apk)\n"
                    + "PSD (ext:psd)\n"
                    + "\n"
                    ;
            // 结果排除默认值：办公临时/备份 + Windows 缓存/系统 + 编程构建产物
            p.excludeText = "~$*\n"
                    + "ext:asd\n"
                    + "ext:wbk\n"
                    + "ext:xlk\n"
                    + "ext:tmp\n"
                    + "ext:temp\n"
                    + "ext:log\n"
                    + "Thumbs.db\n"
                    + "ext:lnk\n"
                    + "ext:chk\n"
                    + "ext:sys\n"
                    + "ext:dll\n"
                    + "ext:ini\n"
                    + "node_modules\n"
                    + "startwith:.\n"
                    + "ext:class\n"
                    + "ext:pyc\n"
                    + "\n"
                    ;
            return p;
        }

        /**
         * 解析快速检索文本：每行 "显示名 (过滤模式)"。
         * 取最后一对括号：括号前为显示名，括号内为搜索关键词（原样发送，不额外处理）。
         * 无括号的行：整行同时作为显示名和搜索关键词。
         * @return [label, pattern] 列表
         */
        static List<String[]> parseQuickSearches(String text) {
            List<String[]> out = new ArrayList<>();
            if (text == null || text.isEmpty()) return out;
            for (String line : text.split("\\r?\\n")) {
                line = line.trim();
                if (line.isEmpty()) continue;
                int open = line.lastIndexOf('(');
                int close = line.lastIndexOf(')');
                if (open > 0 && close > open) {
                    String label = line.substring(0, open).trim();
                    String pattern = line.substring(open + 1, close).trim();
                    if (!label.isEmpty() && !pattern.isEmpty()) {
                        out.add(new String[]{label, pattern});
                        continue;
                    }
                }
                // 无括号或括号不完整 → 整行作为显示名和搜索关键词
                out.add(new String[]{line, line});
            }
            return out;
        }

        /**
         * 解析结果排除文本：每行一个排除表达式，跳过空行。
         * @return 排除表达式列表（不含 ! 前缀）
         */
        static List<String> parseExcludes(String text) {
            List<String> out = new ArrayList<>();
            if (text == null || text.isEmpty()) return out;
            for (String line : text.split("\\r?\\n")) {
                line = line.trim();
                if (!line.isEmpty()) out.add(line);
            }
            return out;
        }
        
    }

    private PathPresets presets;

    // 搜索位置（统一容器）
    private LinearLayout layoutPaths;
    private final Map<String, MaterialCheckBox> pathCheckBoxes = new LinkedHashMap<>();

    // 自定义路径输入
    private TextInputEditText etCustomPath;
    private View btnAdd;

    // ETP 端口
    private TextInputEditText etEtpPort;

    // 匹配选项
    private Chip chipMatchCase, chipWholeWord, chipMatchPath, chipRegex;

    // 排序
    private Spinner spSortField;
    private com.google.android.material.button.MaterialButton btnSortDir;
    // sortFieldName 映射 spinner position → Everything 排序列名
    private static final String[] SORT_NAMES = {
            null, "name", "path", "size", "extension", "date_modified", "date_created"
    };

    // 搜索页显示的排序字段（勾选控制）
    private LinearLayout layoutUiSortFields;
    private static final String[] UI_SORT_KEYS = {
            "name", "size", "path", "extension", "date_modified", "date_created"};
    private static final int[] UI_SORT_LABEL_INDEX = {1, 3, 2, 4, 5, 6}; // 对应 path_preset_sort_fields 数组
    private final Map<String, MaterialCheckBox> uiSortCheckBoxes = new LinkedHashMap<>();

    // 结果限制
    private TextInputEditText etMaxResults;

    // 快速检索（多行文本编辑）
    private TextInputEditText etQuickSearch;

    // 结果排除（多行文本编辑）
    private TextInputEditText etExclude;

    private final Gson gson = new Gson();

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_etp_settings);

        // Toolbar
        androidx.appcompat.widget.Toolbar toolbar = findViewById(R.id.toolbarEtpSettings);
        toolbar.setNavigationOnClickListener(v -> finish());

        // 搜索位置：统一容器
        layoutPaths = findViewById(R.id.layoutPaths);

        // 自定义
        etCustomPath = findViewById(R.id.etCustomPath);
        btnAdd = findViewById(R.id.btnAddPath);

        btnAdd.setOnClickListener(v -> addCustomPath());

        // ETP 端口
        etEtpPort = findViewById(R.id.etEtpPort);

        // 匹配选项
        chipMatchCase = findViewById(R.id.chipMatchCase);
        chipWholeWord = findViewById(R.id.chipWholeWord);
        chipMatchPath = findViewById(R.id.chipMatchPath);
        chipRegex = findViewById(R.id.chipRegex);

        // 排序
        spSortField = findViewById(R.id.spSortField);
        btnSortDir = findViewById(R.id.btnSortDir);
        btnSortDir.setOnClickListener(v -> {
            presets.sortAsc = !presets.sortAsc;
            updateSortDirButton();
        });

        // 搜索页显示的排序字段
        layoutUiSortFields = findViewById(R.id.layoutUiSortFields);

        // 结果限制
        etMaxResults = findViewById(R.id.etMaxResults);

        // 快速检索
        etQuickSearch = findViewById(R.id.etQuickSearch);
        findViewById(R.id.btnRestoreQuickSearch).setOnClickListener(v ->
                etQuickSearch.setText(PathPresets.defaults().quickSearchText));

        // 结果排除
        etExclude = findViewById(R.id.etExclude);
        findViewById(R.id.btnRestoreExclude).setOnClickListener(v ->
                etExclude.setText(PathPresets.defaults().excludeText));

        load();
        applyToUI();
    }

    @Override
    protected void onPause() {
        super.onPause();
        collectFromUI();
        save();
    }

    // ---- 加载/保存 ----

    private void load() {
        String json = App.get().settingsStore.getString(PREF_KEY, null);
        if (json != null) {
            try {
                presets = gson.fromJson(json, PathPresets.class);
                if (presets != null && presets.paths != null && !presets.paths.isEmpty()) return;
            } catch (Exception ignored) {}
        }
        presets = PathPresets.defaults();
    }

    /** 旧配置兼容：uiSortFields 为空时回退到默认 4 个字段。 */
    private void ensureUiSortFields() {
        if (presets.uiSortFields == null) {
            presets.uiSortFields = new ArrayList<>();
        }
        if (presets.uiSortFields.isEmpty()) {
            presets.uiSortFields.add("name");
            presets.uiSortFields.add("size");
            presets.uiSortFields.add("path");
            presets.uiSortFields.add("date_modified");
        }
    }

    private void save() {
        // 保存路径预设
        String json = gson.toJson(presets);
        App.get().settingsStore.putString(PREF_KEY, json);

        // 保存 ETP 端口
        String portStr = etEtpPort.getText().toString().trim();
        int port;
        try {
            port = portStr.isEmpty() ? SettingsStore.DEFAULT_ETP_PORT : Integer.parseInt(portStr);
            if (port <= 0 || port > 65535) port = SettingsStore.DEFAULT_ETP_PORT;
        } catch (NumberFormatException e) {
            port = SettingsStore.DEFAULT_ETP_PORT;
        }
        App.get().settingsStore.putString(SettingsStore.KEY_ETP_PORT, String.valueOf(port));
    }

    // ---- UI ----

    private void applyToUI() {
        rebuildPaths();

        // ETP 端口
        int port = App.get().settingsStore.getEtpPort();
        etEtpPort.setText(String.valueOf(port));

        // 匹配选项
        chipMatchCase.setChecked(presets.matchCase != 0);
        chipWholeWord.setChecked(presets.matchWholeWord != 0);
        chipMatchPath.setChecked(presets.matchPath != 0);
        chipRegex.setChecked(presets.matchRegex != 0);
        rebuildUiSortFields();

        // 排序
        int sortIdx = 0;
        if (presets.sortName != null) {
            for (int i = 0; i < SORT_NAMES.length; i++) {
                if (presets.sortName.equals(SORT_NAMES[i])) { sortIdx = i; break; }
            }
        }
        spSortField.setSelection(sortIdx);
        updateSortDirButton();

        // 结果限制
        etMaxResults.setText(presets.maxResults > 0 ? String.valueOf(presets.maxResults) : "");

        // 快速检索（旧配置无此字段 → 填入默认示例供参考）
        etQuickSearch.setText(presets.quickSearchText != null
                ? presets.quickSearchText : PathPresets.defaults().quickSearchText);

        // 结果排除（旧配置无此字段 → 填入默认示例供参考）
        etExclude.setText(presets.excludeText != null
                ? presets.excludeText : PathPresets.defaults().excludeText);
    }

    /** 构建搜索页排序字段勾选列表。 */
    private void rebuildUiSortFields() {
        ensureUiSortFields();
        layoutUiSortFields.removeAllViews();
        uiSortCheckBoxes.clear();
        String[] labels = getResources().getStringArray(R.array.path_preset_sort_fields);
        for (int i = 0; i < UI_SORT_KEYS.length; i++) {
            String key = UI_SORT_KEYS[i];
            MaterialCheckBox cb = new MaterialCheckBox(this);
            cb.setText(labels[UI_SORT_LABEL_INDEX[i]]);
            cb.setChecked(presets.uiSortFields.contains(key));
            cb.setOnCheckedChangeListener((b, checked) -> {
                if (checked) {
                    if (!presets.uiSortFields.contains(key)) presets.uiSortFields.add(key);
                } else {
                    presets.uiSortFields.remove(key);
                }
            });
            uiSortCheckBoxes.put(key, cb);
            layoutUiSortFields.addView(cb);
        }
    }

    private void updateSortDirButton() {
        btnSortDir.setText(presets.sortAsc
                ? R.string.path_preset_sort_asc : R.string.path_preset_sort_desc);
    }

    private void collectFromUI() {
        for (PathEntry entry : presets.paths) {
            MaterialCheckBox cb = pathCheckBoxes.get(entry.path);
            if (cb != null) {
                entry.checked = cb.isChecked();
            }
        }

        // 匹配选项
        presets.matchCase = chipMatchCase.isChecked() ? 1 : 0;
        presets.matchWholeWord = chipWholeWord.isChecked() ? 1 : 0;
        presets.matchPath = chipMatchPath.isChecked() ? 1 : 0;
        presets.matchRegex = chipRegex.isChecked() ? 1 : 0;

        // 排序
        int sortIdx = spSortField.getSelectedItemPosition();
        presets.sortName = (sortIdx >= 0 && sortIdx < SORT_NAMES.length) ? SORT_NAMES[sortIdx] : null;

        // 结果限制
        String s = etMaxResults.getText().toString().trim();
        try {
            presets.maxResults = s.isEmpty() ? 0 : Integer.parseInt(s);
        } catch (NumberFormatException ignored) {
            presets.maxResults = 0;
        }

        // 快速检索
        presets.quickSearchText = etQuickSearch.getText().toString();

        // 结果排除
        presets.excludeText = etExclude.getText().toString();
    }

    // ---- 动态路径 ----

    private void rebuildPaths() {
        layoutPaths.removeAllViews();
        pathCheckBoxes.clear();

        if (presets.paths == null || presets.paths.isEmpty()) {
            presets.paths = PathPresets.defaults().paths;
        }

        TypedArray a = obtainStyledAttributes(new int[]{android.R.attr.selectableItemBackground});
        int selectableItemBackground = a.getResourceId(0, 0);
        a.recycle();

        for (String type : new String[]{"common", "drive", "custom_path"}) {
            List<PathEntry> group = new ArrayList<>();
            for (PathEntry entry : presets.paths) {
                if (type.equals(entry.type)) group.add(entry);
            }
            if (group.isEmpty()) continue;

            switch (type) {
                case "common": addSectionHeader(R.string.path_preset_common, type); break;
                case "drive": addSectionHeader(R.string.path_preset_drives, type); break;
                case "custom_path": addSectionHeader(R.string.path_preset_custom, type); break;
            }

            for (PathEntry entry : group) {
                addPathCheckBox(entry, selectableItemBackground);
            }
        }
    }

    private void addSectionHeader(int titleRes, String type) {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(this, null, 0, R.style.PathPresetSectionHeader);
        title.setTextAppearance(
                com.google.android.material.R.style.TextAppearance_Material3_TitleSmall);
        title.setText(titleRes);
        title.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(title);

        TextButton restore = new TextButton(this);
        restore.setText(R.string.path_preset_restore_defaults);
        TypedValue tv = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.colorPrimary, tv, true);
        restore.setTextColor(tv.data);
        restore.setOnClickListener(v -> restoreGroupDefaults(type));
        header.addView(restore);

        layoutPaths.addView(header);
    }

    /** 把指定分组的路径恢复为该组的默认预设。 */
    private void restoreGroupDefaults(String type) {
        List<PathEntry> defaults = PathPresets.defaults().paths;
        List<PathEntry> restored = new ArrayList<>();
        for (PathEntry entry : presets.paths) {
            if (!type.equals(entry.type)) restored.add(entry);
        }
        for (PathEntry entry : defaults) {
            if (type.equals(entry.type)) restored.add(entry);
        }
        presets.paths = restored;
        rebuildPaths();
    }

    private void addPathCheckBox(PathEntry entry, int selectableItemBackground) {
        LinearLayout wrapper = new LinearLayout(this);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        wlp.setMargins(2, 2, 2, 2);
        wrapper.setLayoutParams(wlp);
        wrapper.setBackgroundResource(selectableItemBackground);
        wrapper.setClickable(true);
        wrapper.setFocusable(true);
        wrapper.setOrientation(LinearLayout.HORIZONTAL);
        wrapper.setGravity(Gravity.CENTER_VERTICAL);

        MaterialCheckBox cb = new MaterialCheckBox(this);
        cb.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        cb.setBackground(null);
        cb.setText(entry.label);
        cb.setChecked(entry.checked);
        cb.setClickable(false);
        cb.setFocusable(false);
        cb.setGravity(Gravity.CENTER);

        wrapper.setOnClickListener(v -> cb.toggle());
        pathCheckBoxes.put(entry.path, cb);
        wrapper.addView(cb);
        layoutPaths.addView(wrapper);
    }

    // ---- 自定义路径 ----

    private void addCustomPath() {
        String path = etCustomPath.getText().toString().trim();
        if (TextUtils.isEmpty(path)) {
            Toast.makeText(this, R.string.path_preset_empty_hint, Toast.LENGTH_SHORT).show();
            return;
        }
        for (PathEntry entry : presets.paths) {
            if (entry.path.equals(path)) {
                Toast.makeText(this, R.string.path_preset_duplicate, Toast.LENGTH_SHORT).show();
                return;
            }
        }
        presets.paths.add(new PathEntry("custom_path", path, path, true));
        etCustomPath.setText("");
        rebuildPaths();
    }
}
