package github.linsmod.sharetopc.ui;

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
                    App.get().historyStore.clear();
                    Toast.makeText(requireContext(), R.string.history_cleared,
                            Toast.LENGTH_SHORT).show();
                    return true;
                });
            }
        }
    }
}
