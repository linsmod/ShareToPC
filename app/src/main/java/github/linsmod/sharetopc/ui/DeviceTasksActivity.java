package github.linsmod.sharetopc.ui;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.Nullable;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.appbar.MaterialToolbar;

import github.linsmod.sharetopc.MainViewModel;
import github.linsmod.sharetopc.R;
import github.linsmod.sharetopc.discovery.DiscoveredDevice;

/**
 * 设备任务页：顶部返回 + 设备名，内容为按设备筛选的 TaskFragment，
 * 结构与 ETP 搜索/文件浏览一致。从设备列表点击设备进入。
 */
public class DeviceTasksActivity extends androidx.appcompat.app.AppCompatActivity
        implements TaskFragment.DeviceActionListener {

    public static final String EXTRA_DEVICE_NAME = "device_name";
    public static final String EXTRA_HOST = "host";
    public static final String EXTRA_PORT = "port";
    public static final String EXTRA_DEVICE_ID = "device_id";

    private MainViewModel viewModel;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_device_tasks);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.deviceTasksRoot), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        Intent intent = getIntent();
        String host = intent.getStringExtra(EXTRA_HOST);
        String deviceName = intent.getStringExtra(EXTRA_DEVICE_NAME);
        if (TextUtils.isEmpty(host)) {
            Toast.makeText(this, "未指定设备", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        viewModel = new ViewModelProvider(this).get(MainViewModel.class);

        MaterialToolbar toolbar = findViewById(R.id.toolbarDeviceTasks);
        toolbar.setTitle(deviceName != null ? deviceName : "");
        toolbar.setNavigationOnClickListener(v -> finish());

        DiscoveredDevice device = new DiscoveredDevice(deviceName, host,
                intent.getIntExtra(EXTRA_PORT, 0));
        device.deviceId = intent.getStringExtra(EXTRA_DEVICE_ID);
        device.alias = github.linsmod.sharetopc.App.get().settingsStore
                .getDeviceAlias(host, intent.getIntExtra(EXTRA_PORT, 0));
        toolbar.inflateMenu(R.menu.device_tasks_menu);
        toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.action_device_props) {
                showAliasDialog(device);
                return true;
            }
            return false;
        });

        if (savedInstanceState == null) {
            TaskFragment fragment = new TaskFragment();
            Bundle args = new Bundle();
            args.putString(TaskFragment.EXTRA_HOST, host);
            args.putInt(TaskFragment.EXTRA_PORT, intent.getIntExtra(EXTRA_PORT, 0));
            args.putString(TaskFragment.EXTRA_DEVICE_NAME, deviceName);
            fragment.setArguments(args);
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.deviceTasksContent, fragment)
                    .commit();
        }
    }

    @Override
    public void showEtpExplorer(DiscoveredDevice device) {
        Intent intent = new Intent(this, EtpExplorerActivity.class);
        intent.putExtra(EtpExplorerActivity.EXTRA_DEVICE_NAME, device.displayName());
        intent.putExtra(EtpExplorerActivity.EXTRA_HOST, device.host);
        intent.putExtra(EtpExplorerActivity.EXTRA_DEVICE_ID, device.deviceId);
        intent.putExtra(EtpExplorerActivity.EXTRA_PORT, device.port);
        startActivity(intent);
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

    public void showAliasDialog(DiscoveredDevice device) {
        DeviceDialogs.showAliasDialog(this, viewModel, device);
    }
}
