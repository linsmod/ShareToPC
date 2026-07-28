package github.linsmod.sharetopc.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

import github.linsmod.sharetopc.R;
import github.linsmod.sharetopc.discovery.DiscoveredDevice;

/**
 * 设备列表适配器（设备卡片 + 手动添加入口）。
 */
public class DeviceAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TYPE_DEVICE = 0;
    private static final int TYPE_ADD = 1;

    public interface OnDeviceClickListener {
        void onClick(DiscoveredDevice device);
        void onLongClick(DiscoveredDevice device);
    }

    public interface OnAddDeviceClickListener { void onAddDevice(); }

    private final List<DiscoveredDevice> displayItems = new ArrayList<>();
    private final OnDeviceClickListener clickListener;
    private OnAddDeviceClickListener addClickListener;

    public DeviceAdapter(OnDeviceClickListener clickListener) {
        this.clickListener = clickListener;
    }

    public void setOnAddDeviceClickListener(OnAddDeviceClickListener l) { this.addClickListener = l; }

    public void submitList(List<DiscoveredDevice> discoveredDevices) {
        displayItems.clear();
        if (discoveredDevices != null) displayItems.addAll(discoveredDevices);
        notifyDataSetChanged();
    }

    /** 按设备地址刷新进度条（不重建整个列表）。 */
    public void notifyProgressChanged(String deviceAddress) {
        for (int i = 0; i < displayItems.size(); i++) {
            if (displayItems.get(i).address().equals(deviceAddress)) {
                notifyItemChanged(i);
                return;
            }
        }
    }

    @Override
    public int getItemViewType(int position) {
        if (position >= displayItems.size()) return TYPE_ADD;
        return TYPE_DEVICE;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_ADD) {
            return new RecyclerView.ViewHolder(
                    inflater.inflate(R.layout.item_add_device, parent, false)) {};
        }
        return new DeviceVH(inflater.inflate(R.layout.item_device, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (getItemViewType(position) == TYPE_ADD) {
            holder.itemView.setOnClickListener(v -> {
                if (addClickListener != null) addClickListener.onAddDevice();
            });
            return;
        }
        bindDevice((DeviceVH) holder, displayItems.get(position));
    }

    private void bindDevice(DeviceVH vh, DiscoveredDevice device) {
        vh.tvName.setText(device.displayName());
        vh.tvAddr.setText(device.address());

        // 传输进度
        DiscoveredDevice.DeviceProgress progress = device.currentProgress;
        if (progress != null && progress.isActive()) {
            vh.layoutProgress.setVisibility(View.VISIBLE);
            vh.pbProgress.setProgress(progress.percent());
            vh.tvProgress.setText(progress.summary());
            // 当前 entry 名称
            if (progress.currentEntryName != null && !progress.currentEntryName.isEmpty()) {
                vh.tvEntryName.setVisibility(View.VISIBLE);
                vh.tvEntryName.setText(progress.currentEntryName);
            } else {
                vh.tvEntryName.setVisibility(View.GONE);
            }
        } else {
            vh.layoutProgress.setVisibility(View.GONE);
            vh.tvEntryName.setVisibility(View.GONE);
        }

        vh.vOnlineDot.setBackgroundResource(
                device.isOnline ? R.drawable.dot_online : R.drawable.dot_offline);
        vh.itemView.setOnClickListener(v -> {
            if (clickListener != null) clickListener.onClick(device);
        });
        vh.itemView.setOnLongClickListener(v -> {
            if (clickListener != null) { clickListener.onLongClick(device); return true; }
            return false;
        });
    }

    @Override
    public int getItemCount() { return displayItems.size() + 1; }

    // ── ViewHolders ──────────────────────────────────────

    static class DeviceVH extends RecyclerView.ViewHolder {
        final View vOnlineDot;
        final TextView tvName, tvAddr, tvProgress, tvEntryName;
        final LinearLayout layoutProgress;
        final ProgressBar pbProgress;
        DeviceVH(@NonNull View v) {
            super(v);
            vOnlineDot = v.findViewById(R.id.vOnlineDot);
            tvName = v.findViewById(R.id.tvDeviceName);
            tvAddr = v.findViewById(R.id.tvDeviceAddr);
            layoutProgress = v.findViewById(R.id.layoutProgress);
            pbProgress = v.findViewById(R.id.pbDeviceJob);
            tvProgress = v.findViewById(R.id.tvDeviceProgress);
            tvEntryName = v.findViewById(R.id.tvEntryName);
        }
    }
}
