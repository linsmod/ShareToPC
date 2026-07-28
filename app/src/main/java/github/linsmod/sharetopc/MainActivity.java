package github.linsmod.sharetopc;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.bottomnavigation.BottomNavigationView;

import github.linsmod.sharetopc.data.TransferRecord;
import github.linsmod.sharetopc.discovery.DiscoveredDevice;
import github.linsmod.sharetopc.service.ShareForegroundService;
import github.linsmod.sharetopc.ui.DeviceQueueDialog;
import github.linsmod.sharetopc.ui.DevicesFragment;
import github.linsmod.sharetopc.ui.Listeners;
import github.linsmod.sharetopc.ui.TaskFragment;
import github.linsmod.sharetopc.ui.SettingsActivity;

import androidx.fragment.app.Fragment;

public class MainActivity extends AppCompatActivity {

    private MainViewModel viewModel;
    private DeviceQueueDialog deviceQueueDialog;

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

    public void showDeviceQueueDialog(github.linsmod.sharetopc.discovery.DiscoveredDevice device) {
        if (deviceQueueDialog != null && deviceQueueDialog.isShowing()) {
            deviceQueueDialog.dismiss();
        }

        // 从 history LiveData 取快照，过滤该设备的记录，取最近一个 job 构造成 TaskGroup
        github.linsmod.sharetopc.ui.TaskGroup group = null;
        java.util.List<TransferRecord> history = viewModel.getHistory();
        if (history != null) {
            String deviceKey = device.address();
            java.util.List<TransferRecord> scoped = new java.util.ArrayList<>();
            for (TransferRecord r : history) {
                if (r.host != null && deviceKey.equals(r.host + ":" + r.port)) {
                    scoped.add(r);
                }
            }
            if (!scoped.isEmpty()) {
                // 找 timeMillis 最大的 jobId
                String latestJobId = null;
                long latestTime = 0;
                for (TransferRecord r : scoped) {
                    if (latestJobId == null || r.timeMillis > latestTime) {
                        latestTime = r.timeMillis;
                        latestJobId = r.jobId;
                    }
                }
                // 筛选该 jobId 的所有条目
                java.util.List<TransferRecord> latestEntries = new java.util.ArrayList<>();
                if (latestJobId != null) {
                    for (TransferRecord r : scoped) {
                        if (latestJobId.equals(r.jobId)) latestEntries.add(r);
                    }
                }
                if (!latestEntries.isEmpty()) {
                    java.util.Collections.reverse(latestEntries); // DB 查询为 DESC，反转为提交顺序
                    TransferRecord first = latestEntries.get(0);
                    group = new github.linsmod.sharetopc.ui.TaskGroup(latestJobId,
                            first.targetName != null ? first.targetName
                                    : (first.host != null ? first.host : ""),
                            first.host, first.port, latestTime, latestEntries);
                    group.recomputeProgress();
                }
            }
        }

        deviceQueueDialog = new DeviceQueueDialog(this, device, group,
                new Listeners.TaskActionListener() {
            @Override
            public void onGroupMenuRequested(github.linsmod.sharetopc.ui.TaskGroup group,
                                             View anchor, float touchX) {
                // 由 DeviceQueueDialog 内部 showGroupMenu 处理
            }

            @Override
            public void onGroupPauseRequested(github.linsmod.sharetopc.ui.TaskGroup group) {
                viewModel.pauseGroup(group);
                Toast.makeText(MainActivity.this, "已暂停传输", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onGroupResumeRequested(github.linsmod.sharetopc.ui.TaskGroup group) {
                viewModel.resumeGroup(group);
            }

            @Override
            public void onGroupDeleteRequested(github.linsmod.sharetopc.ui.TaskGroup group) {
                viewModel.deleteGroup(group);
                Toast.makeText(MainActivity.this,
                        getString(R.string.group_deleted, group.totalEntries),
                        Toast.LENGTH_SHORT).show();
                if (deviceQueueDialog != null) deviceQueueDialog.dismiss();
            }
        }, transferId -> viewModel.hasPfd(transferId), viewModel);
        deviceQueueDialog.show();
    }

    private void showTextPreview(github.linsmod.sharetopc.data.TransferRecord record) {
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

    public void showAliasDialog(github.linsmod.sharetopc.discovery.DiscoveredDevice device) {
        View layout = getLayoutInflater().inflate(R.layout.dialog_alias, null);

        EditText etAlias = layout.findViewById(R.id.etAlias);
        etAlias.setHint(device.name);
        if (device.alias != null) {
            etAlias.setText(device.alias);
        }

        TextView tvTitle = layout.findViewById(R.id.tvAliasTitle);
        tvTitle.setText(device.name);

        layout.findViewById(R.id.btnPresetLaptop).setOnClickListener(v -> etAlias.setText(R.string.preset_laptop));
        layout.findViewById(R.id.btnPresetOffice).setOnClickListener(v -> etAlias.setText(R.string.preset_office));
        layout.findViewById(R.id.btnPresetDesktop).setOnClickListener(v -> etAlias.setText(R.string.preset_desktop));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(layout)
                .setPositiveButton(R.string.save, (d, which) -> {
                    App.get().settingsStore.setDeviceAlias(device.host, device.port,
                            etAlias.getText().toString());
                    viewModel.refreshDevices();
                })
                .setNegativeButton(R.string.cancel, null)
                .setNeutralButton(R.string.delete_device, (d, which) -> {
                    App.get().deviceStore.deleteDevice(device.host, device.port);
                    viewModel.removeDevice(device);
                })
                .show();

        // 去掉 AlertDialog 内部容器多余 padding
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
        viewModel.startDiscovery();
    }

    @Override
    protected void onStop() {
        super.onStop();
    }
}