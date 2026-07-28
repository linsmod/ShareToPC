package github.linsmod.sharetopc.ui;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import com.google.android.material.appbar.MaterialToolbar;

import github.linsmod.sharetopc.App;
import github.linsmod.sharetopc.R;

/** 设置页：清理策略、手动设备、清空历史。 */
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

            Preference clearHistory = findPreference("clear_history");
            if (clearHistory != null) {
                clearHistory.setOnPreferenceClickListener(preference -> {
                    App.get().jobStore.clear();
                    Toast.makeText(requireContext(), R.string.history_cleared,
                            Toast.LENGTH_SHORT).show();
                    return true;
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
