package github.linsmod.sharetopc.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

import github.linsmod.sharetopc.R;
import github.linsmod.sharetopc.discovery.DiscoveredDevice;

/** 设备列表适配器。 */
public class DeviceAdapter extends RecyclerView.Adapter<DeviceAdapter.VH> {

    /** 当前设备传输状态，用于在设备行显示发送进度。 */
    public static class DeviceTransferInfo {
        public final String deviceKey;
        public final String fileName;
        public final int percent;
        public final boolean active;

        public DeviceTransferInfo(String deviceKey, String fileName, int percent, boolean active) {
            this.deviceKey = deviceKey;
            this.fileName = fileName;
            this.percent = percent;
            this.active = active;
        }
    }

    public interface OnDeviceClickListener {
        void onClick(DiscoveredDevice device);

        /** 长按：编辑设备备注名。 */
        void onLongClick(DiscoveredDevice device);
    }

    private final List<DiscoveredDevice> items = new ArrayList<>();
    private final OnDeviceClickListener clickListener;
    private DeviceTransferInfo currentTransfer;

    public DeviceAdapter(OnDeviceClickListener clickListener) {
        this.clickListener = clickListener;
    }

    public void submitList(List<DiscoveredDevice> list) {
        items.clear();
        if (list != null) {
            items.addAll(list);
        }
        notifyDataSetChanged();
    }

    public void setCurrentTransfer(@Nullable DeviceTransferInfo info) {
        this.currentTransfer = info;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_device, parent, false);
        return new VH(view);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        DiscoveredDevice device = items.get(position);
        holder.tvName.setText(device.displayName());
        holder.tvAddr.setText(device.host + ":" + device.port);

        // 在线/离线圆点
        holder.vOnlineDot.setBackgroundResource(
                device.isOnline ? R.drawable.dot_online : R.drawable.dot_offline);

        boolean isActive = currentTransfer != null && currentTransfer.active
                && currentTransfer.deviceKey.equals(device.key());
        if (isActive) {
            holder.tvProgress.setVisibility(View.VISIBLE);
            holder.tvProgress.setText(currentTransfer.fileName
                    + "  发送中… " + currentTransfer.percent + "%");
        } else {
            holder.tvProgress.setVisibility(View.GONE);
        }

        holder.itemView.setOnClickListener(v -> {
            if (clickListener != null) {
                clickListener.onClick(device);
            }
        });
        holder.itemView.setOnLongClickListener(v -> {
            if (clickListener != null) {
                clickListener.onLongClick(device);
                return true;
            }
            return false;
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final View vOnlineDot;
        final TextView tvName;
        final TextView tvAddr;
        final TextView tvProgress;

        VH(@NonNull View itemView) {
            super(itemView);
            vOnlineDot = itemView.findViewById(R.id.vOnlineDot);
            tvName = itemView.findViewById(R.id.tvDeviceName);
            tvAddr = itemView.findViewById(R.id.tvDeviceAddr);
            tvProgress = itemView.findViewById(R.id.tvDeviceProgress);
        }
    }
}
