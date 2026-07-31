package github.linsmod.sharetopc.ui;

import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.chip.Chip;
import com.google.android.material.materialswitch.MaterialSwitch;
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

    static class PathPresets {
        Map<String, Boolean> builtin = new LinkedHashMap<>();
        Map<String, Boolean> drives = new LinkedHashMap<>();
        List<String> custom = new ArrayList<>();

        // 匹配选项
        int matchCase;
        int matchPath;
        int matchRegex;
        int matchWholeWord;

        // 排序
        String sortName;     // null / "" 表示默认排序
        boolean sortAsc = true;
        // 多字段独立排序状态持久化: fieldName -> 0=none, 1=asc, 2=desc
        Map<String, Integer> sortFields = new LinkedHashMap<>();

        // 结果限制（0 = 不限）
        int maxResults;

        static PathPresets defaults() {
            PathPresets p = new PathPresets();
            p.builtin.put("desktop", false);
            p.builtin.put("documents", false);
            p.builtin.put("downloads", false);
            p.builtin.put("videos", false);
            p.builtin.put("pictures", false);
            p.builtin.put("music", false);
            p.drives.put("C:", false);
            p.drives.put("D:", false);
            p.drives.put("E:", false);
            p.drives.put("F:", false);
            for (String f : new String[]{"name", "size", "path", "extension", "date_modified", "date_created"}) {
                p.sortFields.put(f, 0);
            }
            return p;
        }
    }

    private PathPresets presets;

    // 搜索位置
    private MaterialCheckBox cbDesktop, cbDocuments, cbDownloads, cbVideos, cbPictures, cbMusic;
    // 磁盘
    private MaterialCheckBox cbDriveC, cbDriveD, cbDriveE, cbDriveF;
    // 自定义
    private TextInputEditText etCustomPath;
    private View btnAdd;
    private LinearLayout layoutCustomPaths;

    // ETP 端口
    private TextInputEditText etEtpPort;

    // 匹配选项
    private MaterialSwitch swMatchCase, swWholeWord, swMatchPath, swRegex;

    // 排序
    private Spinner spSortField;
    private com.google.android.material.button.MaterialButton btnSortDir;
    // sortFieldName 映射 spinner position → Everything 排序列名
    private static final String[] SORT_NAMES = {
            null, "name", "path", "size", "extension", "date_modified", "date_created"
    };

    // 结果限制
    private TextInputEditText etMaxResults;

    private final Gson gson = new Gson();

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_etp_settings);

        // Toolbar
        androidx.appcompat.widget.Toolbar toolbar = findViewById(R.id.toolbarEtpSettings);
        toolbar.setNavigationOnClickListener(v -> finish());

        // 搜索位置
        cbDesktop = findViewById(R.id.cbDesktop);
        cbDocuments = findViewById(R.id.cbDocuments);
        cbDownloads = findViewById(R.id.cbDownloads);
        cbVideos = findViewById(R.id.cbVideos);
        cbPictures = findViewById(R.id.cbPictures);
        cbMusic = findViewById(R.id.cbMusic);

        // 磁盘
        cbDriveC = findViewById(R.id.cbDriveC);
        cbDriveD = findViewById(R.id.cbDriveD);
        cbDriveE = findViewById(R.id.cbDriveE);
        cbDriveF = findViewById(R.id.cbDriveF);

        // 自定义
        etCustomPath = findViewById(R.id.etCustomPath);
        btnAdd = findViewById(R.id.btnAddPath);
        layoutCustomPaths = findViewById(R.id.layoutCustomPaths);

        btnAdd.setOnClickListener(v -> addCustomPath());

        // ETP 端口
        etEtpPort = findViewById(R.id.etEtpPort);

        // 匹配选项
        swMatchCase = findViewById(R.id.swMatchCase);
        swWholeWord = findViewById(R.id.swWholeWord);
        swMatchPath = findViewById(R.id.swMatchPath);
        swRegex = findViewById(R.id.swRegex);

        // 排序
        spSortField = findViewById(R.id.spSortField);
        btnSortDir = findViewById(R.id.btnSortDir);
        btnSortDir.setOnClickListener(v -> {
            presets.sortAsc = !presets.sortAsc;
            updateSortDirButton();
        });

        // 结果限制
        etMaxResults = findViewById(R.id.etMaxResults);

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
                if (presets != null) return;
            } catch (Exception ignored) {}
        }
        presets = PathPresets.defaults();
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
        cbDesktop.setChecked(presets.builtin.get("desktop"));
        cbDocuments.setChecked(presets.builtin.get("documents"));
        cbDownloads.setChecked(presets.builtin.get("downloads"));
        cbVideos.setChecked(presets.builtin.get("videos"));
        cbPictures.setChecked(presets.builtin.get("pictures"));
        cbMusic.setChecked(presets.builtin.get("music"));

        cbDriveC.setChecked(presets.drives.get("C:"));
        cbDriveD.setChecked(presets.drives.get("D:"));
        cbDriveE.setChecked(presets.drives.get("E:"));
        cbDriveF.setChecked(presets.drives.get("F:"));

        rebuildCustomPaths();

        // ETP 端口
        int port = App.get().settingsStore.getEtpPort();
        etEtpPort.setText(String.valueOf(port));

        // 匹配选项
        swMatchCase.setChecked(presets.matchCase != 0);
        swWholeWord.setChecked(presets.matchWholeWord != 0);
        swMatchPath.setChecked(presets.matchPath != 0);
        swRegex.setChecked(presets.matchRegex != 0);

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
    }

    private void updateSortDirButton() {
        btnSortDir.setText(presets.sortAsc
                ? R.string.path_preset_sort_asc : R.string.path_preset_sort_desc);
    }

    private void collectFromUI() {
        presets.builtin.put("desktop", cbDesktop.isChecked());
        presets.builtin.put("documents", cbDocuments.isChecked());
        presets.builtin.put("downloads", cbDownloads.isChecked());
        presets.builtin.put("videos", cbVideos.isChecked());
        presets.builtin.put("pictures", cbPictures.isChecked());
        presets.builtin.put("music", cbMusic.isChecked());

        presets.drives.put("C:", cbDriveC.isChecked());
        presets.drives.put("D:", cbDriveD.isChecked());
        presets.drives.put("E:", cbDriveE.isChecked());
        presets.drives.put("F:", cbDriveF.isChecked());

        // 匹配选项
        presets.matchCase = swMatchCase.isChecked() ? 1 : 0;
        presets.matchWholeWord = swWholeWord.isChecked() ? 1 : 0;
        presets.matchPath = swMatchPath.isChecked() ? 1 : 0;
        presets.matchRegex = swRegex.isChecked() ? 1 : 0;

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
    }

    // ---- 自定义路径 ----

    private void addCustomPath() {
        String path = etCustomPath.getText().toString().trim();
        if (TextUtils.isEmpty(path)) {
            Toast.makeText(this, R.string.path_preset_empty_hint, Toast.LENGTH_SHORT).show();
            return;
        }
        if (presets.custom.contains(path)) {
            Toast.makeText(this, R.string.path_preset_duplicate, Toast.LENGTH_SHORT).show();
            return;
        }
        presets.custom.add(path);
        etCustomPath.setText("");
        rebuildCustomPaths();
    }

    private void removeCustomPath(String path) {
        presets.custom.remove(path);
        rebuildCustomPaths();
    }

    private void rebuildCustomPaths() {
        layoutCustomPaths.removeAllViews();
        for (String path : presets.custom) {
            Chip chip = new Chip(this);
            chip.setText(path);
            chip.setCloseIconVisible(true);
            chip.setCloseIconResource(R.drawable.ic_close_chip);
            chip.setClickable(false);
            chip.setCheckable(false);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 4, 8, 4);
            chip.setLayoutParams(lp);
            chip.setOnCloseIconClickListener(v -> removeCustomPath(path));
            layoutCustomPaths.addView(chip);
        }
    }
}
