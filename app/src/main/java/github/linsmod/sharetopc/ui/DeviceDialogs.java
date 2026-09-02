package github.linsmod.sharetopc.ui;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import github.linsmod.sharetopc.App;
import github.linsmod.sharetopc.MainViewModel;
import github.linsmod.sharetopc.R;
import github.linsmod.sharetopc.discovery.DiscoveredDevice;

/** 设备相关对话框的共享实现，供 MainActivity / DeviceTasksActivity 复用。 */
public final class DeviceDialogs {

    private DeviceDialogs() {}

    /** 设备属性/别名对话框：表单展示设备完整属性，仅别名可编辑，删除设备按钮标红。 */
    public static void showAliasDialog(Activity activity, MainViewModel viewModel,
                                       DiscoveredDevice device) {
        View layout = activity.getLayoutInflater().inflate(R.layout.dialog_alias, null);

        TextView tvTitle = layout.findViewById(R.id.tvAliasTitle);
        tvTitle.setText(device.name);

        TextView tvDeviceName = layout.findViewById(R.id.tvDeviceName);
        tvDeviceName.setText(device.name);

        TextView tvDeviceAddress = layout.findViewById(R.id.tvDeviceAddress);
        tvDeviceAddress.setText(device.host + ":" + device.port);

        TextView tvVersion = layout.findViewById(R.id.tvDeviceVersion);
        if (device.version != null && !device.version.isEmpty()) {
            tvVersion.setText(device.version);
            layout.findViewById(R.id.layoutRowVersion).setVisibility(View.VISIBLE);
        }

        TextView tvProtoVersion = layout.findViewById(R.id.tvDeviceProtoVersion);
        if (device.protoVersion != null && !device.protoVersion.isEmpty()) {
            tvProtoVersion.setText("v" + device.protoVersion);
            layout.findViewById(R.id.layoutRowProtoVersion).setVisibility(View.VISIBLE);
        }

        TextView tvDeviceId = layout.findViewById(R.id.tvDeviceId);
        tvDeviceId.setText(device.deviceId != null && !device.deviceId.isEmpty()
                ? device.deviceId.substring(0, Math.min(8, device.deviceId.length()))
                : "未知");

        EditText etAlias = layout.findViewById(R.id.etAlias);
        if (device.alias != null) {
            etAlias.setText(device.alias);
        }

        layout.findViewById(R.id.btnPresetLaptop).setOnClickListener(v -> etAlias.setText(R.string.preset_laptop));
        layout.findViewById(R.id.btnPresetOffice).setOnClickListener(v -> etAlias.setText(R.string.preset_office));
        layout.findViewById(R.id.btnPresetDesktop).setOnClickListener(v -> etAlias.setText(R.string.preset_desktop));

        final AlertDialog[] dialogRef = new AlertDialog[1];
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setView(layout)
                .setPositiveButton(R.string.save, (d, which) -> {
                    App.get().settingsStore.setDeviceAlias(device.host, device.port,
                            etAlias.getText().toString());
                    viewModel.refreshDevices();
                })
                .setNegativeButton(R.string.cancel, null)
                .setNeutralButton(R.string.delete_device, (d, which) -> {
                    new AlertDialog.Builder(activity)
                            .setTitle(R.string.delete_device)
                            .setMessage(activity.getString(R.string.delete_device_confirm, device.name))
                            .setPositiveButton(R.string.delete, (d2, w2) -> {
                                App.get().jobStore.deleteByDevice(device.host, device.port);
                                App.get().deviceStore.deleteDevice(device.host, device.port);
                                viewModel.removeDevice(device);
                                viewModel.refreshHistory();
                                if (dialogRef[0] != null) dialogRef[0].dismiss();
                            })
                            .setNegativeButton(R.string.cancel, null)
                            .show();
                })
                .show();
        dialogRef[0] = dialog;

        // 删除设备按钮标红
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setTextColor(
                androidx.core.content.ContextCompat.getColor(activity, R.color.delete_device_red));

        // 去掉 AlertDialog 内部容器多余 padding
        View customPanel = dialog.findViewById(android.R.id.custom);
        if (customPanel instanceof ViewGroup) {
            ((ViewGroup) customPanel).setPadding(0, 0, 0, 0);
        }
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawableResource(R.drawable.bg_dialog);
            w.setLayout((int) (activity.getResources().getDisplayMetrics().widthPixels * 0.88),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }
}
