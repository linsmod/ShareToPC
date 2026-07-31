package github.linsmod.sharetopc.ui;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.EditTextPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;

import com.google.android.material.appbar.MaterialToolbar;

import github.linsmod.sharetopc.R;

/** 设置页：清理策略、清空历史。 */
public class SettingsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        MaterialToolbar toolbar = findViewById(R.id.toolbarSettings);
        toolbar.setNavigationOnClickListener(v -> finish());

        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.settingsContainer, new SettingsFragment())
                    .commit();
        }
    }

    public static class SettingsFragment extends PreferenceFragmentCompat {
        @Override
        public void onCreatePreferences(@Nullable Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.preferences, rootKey);

            // 本机设备名：未设置时用 Build.MODEL 作为 placeholder 显示
            EditTextPreference deviceNamePref = findPreference("device_name");
            if (deviceNamePref != null) {
                String model = Build.MODEL != null ? Build.MODEL : "Android";
                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());
                String current = prefs.getString("device_name", null);
                if (current != null && !current.trim().isEmpty()) {
                    deviceNamePref.setSummary(current.trim());
                } else {
                    deviceNamePref.setSummary(model + "（系统默认）");
                }
                deviceNamePref.setOnPreferenceChangeListener((preference, newValue) -> {
                    String val = newValue != null ? newValue.toString().trim() : "";
                    if (val.isEmpty()) {
                        deviceNamePref.setSummary(model + "（系统默认）");
                        prefs.edit().remove("device_name").apply();
                        return false; // 让 Preference 框架不要存空字符串
                    } else {
                        deviceNamePref.setSummary(val);
                        return true;
                    }
                });
            }

            Preference viewLogs = findPreference("view_logs");
            if (viewLogs != null) {
                viewLogs.setOnPreferenceClickListener(preference -> {
                    startActivity(new Intent(requireContext(), LogViewerActivity.class));
                    return true;
                });
            }

            Preference qqGroup = findPreference("qq_group");
            if (qqGroup != null) {
                qqGroup.setOnPreferenceClickListener(preference -> {
                    android.content.ClipboardManager clipboard =
                            (android.content.ClipboardManager) requireContext()
                                    .getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                    android.content.ClipData clip =
                            android.content.ClipData.newPlainText("qq_group", "378352638");
                    clipboard.setPrimaryClip(clip);
                    Toast.makeText(requireContext(), "QQ群号已复制", Toast.LENGTH_SHORT).show();
                    return true;
                });
            }

            Preference contactDev = findPreference("contact_dev");
            if (contactDev != null) {
                contactDev.setOnPreferenceClickListener(preference -> {
                    android.content.ClipboardManager clipboard =
                            (android.content.ClipboardManager) requireContext()
                                    .getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                    android.content.ClipData clip =
                            android.content.ClipData.newPlainText("contact_dev", "linsmod@qq.com");
                    clipboard.setPrimaryClip(clip);
                    Toast.makeText(requireContext(), "邮箱已复制", Toast.LENGTH_SHORT).show();
                    return true;
                });
            }

            Preference etpSearch = findPreference("etp_file_search");
            if (etpSearch != null) {
                etpSearch.setOnPreferenceClickListener(preference -> {
                    startActivity(new Intent(requireContext(), EtpSearchActivity.class));
                    return true;
                });
            }

            Preference version = findPreference("version");
            if (version != null) {
                try {
                    String versionName = requireContext().getPackageManager()
                            .getPackageInfo(requireContext().getPackageName(), 0).versionName;
                    version.setSummary(versionName);
                } catch (PackageManager.NameNotFoundException ignored) {
                }
            }
        }

    }
}
