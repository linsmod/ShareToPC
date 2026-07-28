package github.linsmod.sharetopc;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
import android.widget.LinearLayout;
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
import github.linsmod.sharetopc.service.ShareForegroundService;
import github.linsmod.sharetopc.ui.DeviceQueueDialog;
import github.linsmod.sharetopc.ui.DevicesFragment;
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
        deviceQueueDialog = new DeviceQueueDialog(this, device, new DeviceQueueDialog.Listener() {
            @Override
            public void onTextPreviewRequested(github.linsmod.sharetopc.data.TransferRecord record) {
                showTextPreview(record);
            }

            @Override
            public void onPauseRequested(TransferRecord record) {
                viewModel.pauseTransfer(record);
            }

            @Override
            public void onResumeRequested(github.linsmod.sharetopc.data.TransferRecord record) {
                viewModel.retry(record);
            }
        }, this, viewModel.getTasks(), transferId -> viewModel.hasPfd(transferId));
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
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad / 2, pad, pad / 2);

        EditText etAlias = new EditText(this);
        etAlias.setHint(device.name);
        etAlias.setInputType(InputType.TYPE_CLASS_TEXT);
        if (device.alias != null) {
            etAlias.setText(device.alias);
        }
        layout.addView(etAlias);

        LinearLayout presets = new LinearLayout(this);
        presets.setOrientation(LinearLayout.HORIZONTAL);
        String[] presetNames = {
                getString(R.string.preset_laptop),
                getString(R.string.preset_office),
                getString(R.string.preset_desktop)
        };
        for (String preset : presetNames) {
            android.widget.Button btn = new android.widget.Button(this, null,
                    android.R.attr.borderlessButtonStyle);
            btn.setText(preset);
            btn.setTextSize(12f);
            btn.setPadding(0, 0, 0, 0);
            btn.setOnClickListener(v -> etAlias.setText(preset));
            presets.addView(btn);
        }
        layout.addView(presets);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.device_alias_title)
                .setView(layout)
                .setPositiveButton(R.string.save, (d, which) -> {
                    App.get().settingsStore.setDeviceAlias(device.host, device.port,
                            etAlias.getText().toString());
                    viewModel.refreshDevices();
                })
                .setNegativeButton(R.string.cancel, null)
                .setNeutralButton(R.string.delete_device, (d, which) -> {
                    App.get().settingsStore.deleteManualDevice(device.host, device.port);
                    viewModel.removeDevice(device);
                })
                .show();
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawableResource(R.drawable.bg_dialog);
            w.setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.88),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    public void showManualDeviceDialog() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad / 2, pad, pad / 2);

        EditText etHost = new EditText(this);
        etHost.setHint(R.string.manual_host_hint);
        etHost.setInputType(InputType.TYPE_CLASS_TEXT);
        etHost.setText(App.get().settingsStore.getManualHost());
        layout.addView(etHost);

        EditText etPort = new EditText(this);
        etPort.setHint(R.string.manual_port_hint);
        etPort.setInputType(InputType.TYPE_CLASS_NUMBER);
        etPort.setText(String.valueOf(App.get().settingsStore.getManualPort()));
        layout.addView(etPort);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.manual_device_dialog_title)
                .setView(layout)
                .setPositiveButton(R.string.ok, (d, which) -> {
                    String host = etHost.getText().toString().trim();
                    String port = etPort.getText().toString().trim();
                    App.get().settingsStore.saveManualDevice(host, port);
                    viewModel.refreshDevices();
                    Toast.makeText(this, R.string.ok, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawableResource(R.drawable.bg_dialog);
            w.setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.88),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
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