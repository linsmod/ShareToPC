package github.linsmod.sharetopc.ui;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import com.google.android.material.appbar.MaterialToolbar;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

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

            Preference pcClient = findPreference("pc_client");
            if (pcClient != null) {
                pcClient.setOnPreferenceClickListener(preference -> {
                    sharePcClient();
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

        private void sharePcClient() {
            final String fileName = "ShareToPcReciver.zip";
            new Thread(() -> {
                try {
                    File cacheDir = new File(requireContext().getCacheDir(), "pc_client");
                    if (!cacheDir.exists()) cacheDir.mkdirs();
                    File outFile = new File(cacheDir, fileName);

                    try (InputStream in = requireContext().getAssets().open(fileName);
                         FileOutputStream out = new FileOutputStream(outFile)) {
                        byte[] buf = new byte[8192];
                        int len;
                        while ((len = in.read(buf)) > 0) {
                            out.write(buf, 0, len);
                        }
                    }

                    Uri uri = FileProvider.getUriForFile(requireContext(),
                            requireContext().getPackageName() + ".fileprovider", outFile);

                    Intent share = new Intent(Intent.ACTION_SEND);
                    share.setType("application/zip");
                    share.putExtra(Intent.EXTRA_STREAM, uri);
                    share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

                    requireActivity().runOnUiThread(() ->
                            startActivity(Intent.createChooser(share, "分享电脑端程序")));
                } catch (Exception e) {
                    requireActivity().runOnUiThread(() ->
                            Toast.makeText(requireContext(), "分享失败: " + e.getMessage(),
                                    Toast.LENGTH_SHORT).show());
                }
            }).start();
        }
    }
}
