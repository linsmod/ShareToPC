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
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;

import java.util.ArrayList;
import java.util.List;

import github.linsmod.sharetopc.MainViewModel;
import github.linsmod.sharetopc.R;
import github.linsmod.sharetopc.discovery.DiscoveredDevice;
import github.linsmod.sharetopc.transfer.DeviceUserInfo;
import github.linsmod.sharetopc.transfer.RemoteDrive;

/**
 * 设备操作页：顶部返回 + 设备名，下方为分类快捷入口网格。
 */
public class DeviceTasksActivity extends androidx.appcompat.app.AppCompatActivity {

    public static final String EXTRA_DEVICE_NAME = "device_name";
    public static final String EXTRA_HOST = "host";
    public static final String EXTRA_PORT = "port";
    public static final String EXTRA_ETP_PORT = "etp_port";
    public static final String EXTRA_DEVICE_ID = "device_id";

    private MainViewModel viewModel;
    private DiscoveredDevice device;

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

        device = new DiscoveredDevice(deviceName, host,
                intent.getIntExtra(EXTRA_PORT, 0));
        device.deviceId = intent.getStringExtra(EXTRA_DEVICE_ID);
        device.alias = github.linsmod.sharetopc.App.get().settingsStore
                .getDeviceAlias(host, intent.getIntExtra(EXTRA_PORT, 0));

        // Section title: "devicename/username的文件"
        // (now handled by adapter header)

        setupGrid();
    }

    private void setupGrid() {
        List<Object> items = new ArrayList<>();

        // Section 1: 用户文件分类
        String userName = DeviceUserInfo.getUserName(device.host);
        String titleDeviceName = device.displayName();
        String titleUserName = userName != null ? userName : "";
        items.add(new DeviceTasksAdapter.HeaderItem(
                getString(R.string.device_tasks_section_title, titleUserName)));

        // 从设置加载快速检索类目
        EtpSettingsActivity.PathPresets presets = EtpExplorerViewModel.loadPresets();
        List<EtpSettingsActivity.PathPresets.QuickSearchCategory> quickSearches = EtpSettingsActivity.PathPresets.parseQuickSearches(presets.quickSearchText);
        for (EtpSettingsActivity.PathPresets.QuickSearchCategory qs : quickSearches) {
            items.add(new DeviceTasksAdapter.ActionItem(qs.label, qs.iconRes, qs.colorRes, qs.label));
        }

        // Section 2: 磁盘文件
        List<RemoteDrive> drives = DeviceUserInfo.getDrives(device.host);
        items.add(new DeviceTasksAdapter.HeaderItem("磁盘文件", "搜索文件"));
        if (drives != null && !drives.isEmpty()) {
            for (RemoteDrive d : drives) {
                String driveLabel = d.label != null && !d.label.isEmpty()
                        ? d.label + " (" + d.name + ")"
                        : d.name;
                String drivePath = d.name.endsWith("\\") ? d.name : d.name + "\\";
                items.add(new DeviceTasksAdapter.ListItem(driveLabel, R.drawable.ic_folder, drivePath));
            }
        }

        DeviceTasksAdapter adapter = new DeviceTasksAdapter(new DeviceTasksAdapter.OnItemClickListener() {
            @Override
            public void onClick(DeviceTasksAdapter.ActionItem item) {
                onActionClick(item);
            }

            @Override
            public void onListItemClick(DeviceTasksAdapter.ListItem item) {
                onDriveClick(item);
            }

            @Override
            public void onHeaderActionClick(String actionText) {
                DeviceTasksActivity.this.onHeaderActionClick(actionText);
            }
        });
        adapter.setItems(items);

        RecyclerView rv = findViewById(R.id.rvCategoryActions);
        GridLayoutManager glm = new GridLayoutManager(this, 3);
        glm.setSpanSizeLookup(new DeviceTasksAdapter.SpanSizeProvider(adapter.getItems()));
        rv.setLayoutManager(glm);
        rv.setAdapter(adapter);
    }

    private void onActionClick(DeviceTasksAdapter.ActionItem item) {
        showEtpExplorerWithCategory(device, item.category);
    }

    private void onDriveClick(DeviceTasksAdapter.ListItem item) {
        if (item.browsePath != null) {
            showEtpExplorerBrowse(device, item.browsePath);
        }
    }

    private void onHeaderActionClick(String actionText) {
        if ("搜索文件".equals(actionText)) {
            showEtpSearch(device);
        }
    }

    public void showEtpExplorer(DiscoveredDevice device) {
        Intent intent = new Intent(this, EtpExplorerActivity.class);
        intent.putExtra(EtpExplorerActivity.EXTRA_DEVICE_NAME, device.displayName());
        intent.putExtra(EtpExplorerActivity.EXTRA_HOST, device.host);
        intent.putExtra(EtpExplorerActivity.EXTRA_DEVICE_ID, device.deviceId);
        intent.putExtra(EtpExplorerActivity.EXTRA_PORT, device.port);
        intent.putExtra(EtpExplorerActivity.EXTRA_ETP_PORT, device.etpPort);
        startActivity(intent);
    }

    public void showEtpExplorerWithCategory(DiscoveredDevice device, String category) {
        Intent intent = new Intent(this, EtpExplorerActivity.class);
        intent.putExtra(EtpExplorerActivity.EXTRA_DEVICE_NAME, device.displayName());
        intent.putExtra(EtpExplorerActivity.EXTRA_HOST, device.host);
        intent.putExtra(EtpExplorerActivity.EXTRA_DEVICE_ID, device.deviceId);
        intent.putExtra(EtpExplorerActivity.EXTRA_PORT, device.port);
        intent.putExtra(EtpExplorerActivity.EXTRA_FILTER_CATEGORY, category);
        intent.putExtra(EtpExplorerActivity.EXTRA_ETP_PORT, device.etpPort);
        startActivity(intent);
    }

    public void showEtpExplorerBrowse(DiscoveredDevice device, String path) {
        Intent intent = new Intent(this, EtpExplorerActivity.class);
        intent.putExtra(EtpExplorerActivity.EXTRA_DEVICE_NAME, device.displayName());
        intent.putExtra(EtpExplorerActivity.EXTRA_HOST, device.host);
        intent.putExtra(EtpExplorerActivity.EXTRA_DEVICE_ID, device.deviceId);
        intent.putExtra(EtpExplorerActivity.EXTRA_PORT, device.port);
        intent.putExtra(EtpExplorerActivity.EXTRA_INITIAL_PATH, path);
        intent.putExtra(EtpExplorerActivity.EXTRA_ETP_PORT, device.etpPort);
        startActivity(intent);
    }

    public void showEtpSearch(DiscoveredDevice device) {
        Intent intent = new Intent(this, EtpSearchActivity.class);
        intent.putExtra(EtpSearchActivity.EXTRA_DEVICE_NAME, device.displayName());
        intent.putExtra(EtpSearchActivity.EXTRA_HOST, device.host);
        intent.putExtra(EtpSearchActivity.EXTRA_DEVICE_ID, device.deviceId);
        intent.putExtra(EtpSearchActivity.EXTRA_PORT, device.port);
        intent.putExtra(EtpSearchActivity.EXTRA_ETP_PORT, device.etpPort);
        startActivity(intent);
    }
}
