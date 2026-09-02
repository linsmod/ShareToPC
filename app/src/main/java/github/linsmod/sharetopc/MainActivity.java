package github.linsmod.sharetopc;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.bottomnavigation.BottomNavigationView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

import github.linsmod.sharetopc.discovery.DiscoveredDevice;
import github.linsmod.sharetopc.service.ShareForegroundService;
import github.linsmod.sharetopc.share.ShareReceiverActivity;
import github.linsmod.sharetopc.ui.DevicesFragment;
import github.linsmod.sharetopc.ui.DeviceTasksActivity;
import github.linsmod.sharetopc.ui.EtpExplorerActivity;
import github.linsmod.sharetopc.ui.EtpSearchActivity;
import github.linsmod.sharetopc.ui.TaskFragment;
import github.linsmod.sharetopc.ui.SettingsActivity;

import androidx.fragment.app.Fragment;

public class MainActivity extends AppCompatActivity implements TaskFragment.DeviceActionListener {

    private MainViewModel viewModel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        viewModel = new ViewModelProvider(this).get(MainViewModel.class);

        BottomNavigationView bottomNav = findViewById(R.id.bottomNav);
        bottomNav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == R.id.nav_devices) {
                switchFragment(new DevicesFragment());
                return true;
            } else if (id == R.id.nav_tasks) {
                switchFragment(new TaskFragment());
                return true;
            }
            return false;
        });

        findViewById(R.id.btnSettings).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));

        if (savedInstanceState == null) {
            switchFragment(new DevicesFragment());
        }

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                setEnabled(false);
                getOnBackPressedDispatcher().onBackPressed();
            }
        });
    }

    private void switchFragment(Fragment fragment) {
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.navHost, fragment)
                .commit();
    }

    public void showTaskForDevice(github.linsmod.sharetopc.discovery.DiscoveredDevice device) {
        Intent intent = new Intent(this, DeviceTasksActivity.class);
        intent.putExtra(DeviceTasksActivity.EXTRA_DEVICE_NAME, device.displayName());
        intent.putExtra(DeviceTasksActivity.EXTRA_HOST, device.host);
        intent.putExtra(DeviceTasksActivity.EXTRA_PORT, device.port);
        intent.putExtra(DeviceTasksActivity.EXTRA_DEVICE_ID, device.deviceId);
        startActivity(intent);
    }

    private void showTextPreview(github.linsmod.sharetopc.data.SendEntry record) {
        TextView tv = new TextView(this);
        tv.setText(record.textContent);
        tv.setTextIsSelectable(true);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        tv.setPadding(pad, pad / 2, pad, 0);

        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(tv);

        new AlertDialog.Builder(this)
                .setTitle(R.string.text_preview_title)
                .setView(scroll)
                .setPositiveButton(R.string.copy, (dialog, which) -> {
                    android.content.ClipboardManager cm =
                            (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(android.content.ClipData.newPlainText(
                            getString(R.string.text_preview_title), record.textContent));
                    Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show();
                })
                .setNeutralButton(R.string.share, (dialog, which) -> {
                    Intent sendIntent = new Intent(Intent.ACTION_SEND);
                    sendIntent.setType("text/plain");
                    sendIntent.putExtra(Intent.EXTRA_TEXT, record.textContent);
                    startActivity(Intent.createChooser(sendIntent, getString(R.string.share)));
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    @Override
    public void showEtpSearch(DiscoveredDevice device) {
        Intent intent = new Intent(this, EtpSearchActivity.class);
        intent.putExtra(EtpSearchActivity.EXTRA_DEVICE_NAME, device.displayName());
        intent.putExtra(EtpSearchActivity.EXTRA_HOST, device.host);
        intent.putExtra(EtpSearchActivity.EXTRA_DEVICE_ID, device.deviceId);
        intent.putExtra(EtpSearchActivity.EXTRA_PORT, device.port);
        startActivity(intent);
    }
    @Override
    public void showEtpExplorer(DiscoveredDevice device) {
        showEtpExplorerWithCategory(device, null);
    }

    public void showEtpExplorerWithCategory(DiscoveredDevice device, String category) {
        Intent intent = new Intent(this, EtpExplorerActivity.class);
        intent.putExtra(EtpExplorerActivity.EXTRA_DEVICE_NAME, device.displayName());
        intent.putExtra(EtpExplorerActivity.EXTRA_HOST, device.host);
        intent.putExtra(EtpExplorerActivity.EXTRA_DEVICE_ID, device.deviceId);
        intent.putExtra(EtpExplorerActivity.EXTRA_PORT, device.port);
        if (category != null) {
            intent.putExtra(EtpExplorerActivity.EXTRA_FILTER_CATEGORY, category);
        }
        startActivity(intent);
    }

    public void showAliasDialog(github.linsmod.sharetopc.discovery.DiscoveredDevice device) {
        github.linsmod.sharetopc.ui.DeviceDialogs.showAliasDialog(this, viewModel, device);
    }

    public void showManualDeviceDialog() {
        View layout = getLayoutInflater().inflate(R.layout.dialog_manual_device, null);

        EditText etHost = layout.findViewById(R.id.etHost);

        EditText etPort = layout.findViewById(R.id.etPort);

        layout.findViewById(R.id.btnIpPreset1).setOnClickListener(v -> {
            String ip = getString(R.string.preset_ip_192_168_1).replace("?", "");
            etHost.setText(ip);
            etHost.setSelection(ip.length());
        });
        layout.findViewById(R.id.btnIpPreset2).setOnClickListener(v -> {
            String ip = getString(R.string.preset_ip_192_168_0).replace("?", "");
            etHost.setText(ip);
            etHost.setSelection(ip.length());
        });
        layout.findViewById(R.id.btnIpPreset3).setOnClickListener(v -> {
            String ip = getString(R.string.preset_ip_10_0_0).replace("?", "");
            etHost.setText(ip);
            etHost.setSelection(ip.length());
        });

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(layout)
                .setPositiveButton(R.string.ok, null)
                .setNegativeButton(R.string.cancel, null)
                .show();

        // 点击确定时校验 IP 和端口格式
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String host = etHost.getText().toString().trim();
            String portStr = etPort.getText().toString().trim();

            if (!isValidIp(host)) {
                Toast.makeText(this, "IP 地址格式不正确", Toast.LENGTH_SHORT).show();
                return;
            }
            if (!isValidPort(portStr)) {
                Toast.makeText(this, "端口范围为 1~65535", Toast.LENGTH_SHORT).show();
                return;
            }

            int port = Integer.parseInt(portStr);

            DiscoveredDevice device = new DiscoveredDevice("手动设备", host, port, false);
            App.get().discoveryManager.addDevice(device);
            viewModel.refreshDevices();
            Toast.makeText(this, R.string.ok, Toast.LENGTH_SHORT).show();
            dialog.dismiss();
        });

        View customPanel = dialog.findViewById(android.R.id.custom);
        if (customPanel instanceof ViewGroup) {
            ((ViewGroup) customPanel).setPadding(0, 0, 0, 0);
        }
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawableResource(R.drawable.bg_dialog);
            w.setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.88),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    public void showPcClientDialog() {
        View layout = getLayoutInflater().inflate(R.layout.dialog_pc_client, null);

        Button btnShare = layout.findViewById(R.id.btnSharePcClient);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(layout)
                .setNegativeButton(R.string.close, null)
                .show();

        btnShare.setOnClickListener(v -> {
            sharePcClientZip();
            dialog.dismiss();
        });

        View customPanel = dialog.findViewById(android.R.id.custom);
        if (customPanel instanceof ViewGroup) {
            ((ViewGroup) customPanel).setPadding(0, 0, 0, 0);
        }
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawableResource(R.drawable.bg_dialog);
            w.setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.88),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    public void showShareClipboardDialog() {
        // 复用分享文本/文件的对话框，将剪贴板文本作为 text/plain 转发给 ShareReceiverActivity
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        ClipData clip = cm.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) {
            Toast.makeText(this, R.string.clipboard_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        String text = clip.getItemAt(0).coerceToText(this).toString();
        if (text.isEmpty()) {
            Toast.makeText(this, R.string.clipboard_no_text, Toast.LENGTH_SHORT).show();
            return;
        }

        Intent intent = new Intent(this, ShareReceiverActivity.class);
        intent.setAction(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TEXT, text);
        startActivity(intent);
    }

    private void sharePcClientZip() {
        final String fileName = "ShareToPcReciver.zip";
        new Thread(() -> {
            try {
                File cacheDir = new File(getCacheDir(), "pc_client");
                if (!cacheDir.exists()) cacheDir.mkdirs();
                File outFile = new File(cacheDir, fileName);

                try (InputStream in = getAssets().open(fileName);
                     FileOutputStream out = new FileOutputStream(outFile)) {
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = in.read(buf)) > 0) {
                        out.write(buf, 0, len);
                    }
                }

                Uri uri = FileProvider.getUriForFile(this,
                        getPackageName() + ".fileprovider", outFile);

                Intent share = new Intent(Intent.ACTION_SEND);
                share.setType("application/zip");
                share.putExtra(Intent.EXTRA_STREAM, uri);
                share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

                runOnUiThread(() ->
                        startActivity(Intent.createChooser(share, "分享电脑端程序")));
            } catch (Exception e) {
                runOnUiThread(() ->
                        Toast.makeText(this, "分享失败: " + e.getMessage(),
                                Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private static boolean isValidIp(String ip) {
        if (ip == null || ip.isEmpty()) return false;
        String[] parts = ip.split("\\.");
        if (parts.length != 4) return false;
        for (String part : parts) {
            try {
                int n = Integer.parseInt(part);
                if (n < 0 || n > 255) return false;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return true;
    }

    private static boolean isValidPort(String port) {
        if (port == null || port.isEmpty()) return false;
        try {
            int p = Integer.parseInt(port);
            return p >= 1 && p <= 65535;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        Intent serviceIntent = new Intent(this, ShareForegroundService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
        viewModel.refreshDevices();
    }

    @Override
    protected void onStop() {
        super.onStop();
    }
}