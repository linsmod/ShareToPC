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
import java.util.Locale;

import github.linsmod.sharetopc.R;
import github.linsmod.sharetopc.discovery.DiscoveredDevice;

/**
 * 设备列表适配器（设备卡片 + 手动添加入口）。
 */
public class DeviceAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TYPE_DEVICE = 0;
    private static final int TYPE_ADD = 1;
    private static final int TYPE_PC_CLIENT = 2;

    public interface OnDeviceClickListener {
        void onClick(DiscoveredDevice device);
        void onLongClick(DiscoveredDevice device);
    }

    public interface OnAddDeviceClickListener { void onAddDevice(); }
    public interface OnPcClientClickListener { void onPcClient(); }

    private final List<DiscoveredDevice> displayItems = new ArrayList<>();
    private final OnDeviceClickListener clickListener;
    private OnAddDeviceClickListener addClickListener;
    private OnPcClientClickListener pcClientClickListener;

    public DeviceAdapter(OnDeviceClickListener clickListener) {
        this.clickListener = clickListener;
    }

    public void setOnAddDeviceClickListener(OnAddDeviceClickListener l) { this.addClickListener = l; }
    public void setOnPcClientClickListener(OnPcClientClickListener l) { this.pcClientClickListener = l; }

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
        if (position == displayItems.size()) return TYPE_ADD;
        if (position > displayItems.size()) return TYPE_PC_CLIENT;
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
        if (viewType == TYPE_PC_CLIENT) {
            return new RecyclerView.ViewHolder(
                    inflater.inflate(R.layout.item_pc_client, parent, false)) {};
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
        if (getItemViewType(position) == TYPE_PC_CLIENT) {
            holder.itemView.setOnClickListener(v -> {
                if (pcClientClickListener != null) pcClientClickListener.onPcClient();
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
            // 已完成 → 显示 "传输完成 · {时间}"
            if (progress.state == DiscoveredDevice.DeviceProgress.STATE_COMPLETED
                    && progress.completedAt > 0) {
                vh.layoutEntryInfo.setVisibility(View.VISIBLE);
                vh.tvEntryName.setText("传输完成 · " + TaskAdapter.friendlyTime(progress.completedAt));
                vh.tvEntrySize.setVisibility(View.GONE);
            } else if (progress.currentEntryName != null && !progress.currentEntryName.isEmpty()) {
                vh.layoutEntryInfo.setVisibility(View.VISIBLE);
                vh.tvEntryName.setText(progress.currentEntryName);
                if (progress.totalBytes > 0) {
                    vh.tvEntrySize.setVisibility(View.VISIBLE);
                    vh.tvEntrySize.setText(formatSize(progress.totalBytes));
                } else {
                    vh.tvEntrySize.setVisibility(View.GONE);
                }
            } else {
                vh.layoutEntryInfo.setVisibility(View.GONE);
            }
        } else {
            vh.layoutProgress.setVisibility(View.GONE);
            // 进度归 IDLE 但仍保留完成时间时，显示一条简短完成提示
            if (progress != null && progress.completedAt > 0) {
                vh.layoutEntryInfo.setVisibility(View.VISIBLE);
                vh.tvEntryName.setText("传输完成 · " + TaskAdapter.friendlyTime(progress.completedAt));
                vh.tvEntrySize.setVisibility(View.GONE);
            } else {
                vh.layoutEntryInfo.setVisibility(View.GONE);
            }
        }
        // 过期 · 部分完成提示优先于"传输完成"
        if (progress.hasOutdate && progress.partialSuccess) {
            vh.layoutEntryInfo.setVisibility(View.VISIBLE);
            vh.tvEntryName.setText("已过期 · 部分完成 · " + TaskAdapter.friendlyTime(progress.lastUpdateAt));
            vh.tvEntrySize.setVisibility(View.GONE);
        }
        else if(progress.hasOutdate){
            vh.layoutEntryInfo.setVisibility(View.VISIBLE);
            vh.tvEntryName.setText("已过期 · " + TaskAdapter.friendlyTime(progress.lastUpdateAt));
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
    public int getItemCount() { return displayItems.size() + 2; }

    /** 将字节数转为友好文案，如 "1.2 MB""340 KB"。 */
    @NonNull
    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        float kb = bytes / 1024f;
        if (kb < 1024) return String.format(Locale.getDefault(), "%.1f KB", kb);
        float mb = kb / 1024f;
        if (mb < 1024) return String.format(Locale.getDefault(), "%.1f MB", mb);
        float gb = mb / 1024f;
        return String.format(Locale.getDefault(), "%.2f GB", gb);
    }

    // ── ViewHolders ──────────────────────────────────────

    static class DeviceVH extends RecyclerView.ViewHolder {
        final View vOnlineDot;
        final TextView tvName, tvAddr, tvProgress, tvEntryName, tvEntrySize;
        final LinearLayout layoutProgress, layoutEntryInfo;
        final ProgressBar pbProgress;
        DeviceVH(@NonNull View v) {
            super(v);
            vOnlineDot = v.findViewById(R.id.vOnlineDot);
            tvName = v.findViewById(R.id.tvDeviceName);
            tvAddr = v.findViewById(R.id.tvDeviceAddr);
            layoutProgress = v.findViewById(R.id.layoutProgress);
            pbProgress = v.findViewById(R.id.pbDeviceJob);
            tvProgress = v.findViewById(R.id.tvDeviceProgress);
            layoutEntryInfo = v.findViewById(R.id.layoutEntryInfo);
            tvEntryName = v.findViewById(R.id.tvEntryName);
            tvEntrySize = v.findViewById(R.id.tvEntrySize);
        }
    }
}
