package github.linsmod.sharetopc.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import github.linsmod.sharetopc.R;
import github.linsmod.sharetopc.data.SendEntry;
import github.linsmod.sharetopc.data.TransferRecord;
import github.linsmod.sharetopc.discovery.DiscoveredDevice;
import github.linsmod.sharetopc.util.FileUtils;
import github.linsmod.sharetopc.util.TransferStatusHelper;

/**
 * 设备列表适配器（设备 / 传输子项 / 手动添加入口 三种 ViewType）。
 * <p>
 * 通过 {@link DiscoveredDevice#lastJobId} 关联设备与其当前传输记录，
 * 无需外部维护 deviceKey→TransferRecord 的映射。
 */
public class DeviceAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TYPE_DEVICE = 0;
    private static final int TYPE_TRANSFER = 1;
    private static final int TYPE_ADD = 2;

    public interface OnDeviceClickListener {
        void onClick(DiscoveredDevice device);
        void onLongClick(DiscoveredDevice device);
    }

    public interface OnAddDeviceClickListener { void onAddDevice(); }
    private final List<Object> displayItems = new ArrayList<>();
    private final List<DiscoveredDevice> items = new ArrayList<>();
    private final OnDeviceClickListener clickListener;
    private OnAddDeviceClickListener addClickListener;

    private RecyclerView recyclerView;
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault());

    public DeviceAdapter(OnDeviceClickListener clickListener) {
        this.clickListener = clickListener;
    }

    public void setOnAddDeviceClickListener(OnAddDeviceClickListener l) { this.addClickListener = l; }

    private void updateTransferProgressViews(TransferRecord record) {
        if (recyclerView == null || record == null) return;
        String targetKey = record.host + ":" + record.port;
        for (int i = 0; i < displayItems.size(); i++) {
            Object item = displayItems.get(i);
            if (item instanceof TransferRecord) {
                TransferRecord display = (TransferRecord) item;
                if (targetKey.equals(display.host + ":" + display.port)) {
                    // 用新鲜 record 替换 displayItems 中的旧拷贝 + 绑定视图
                    displayItems.set(i, record);
                    TransferVH holder = (TransferVH) recyclerView.findViewHolderForAdapterPosition(i);
                    if (holder != null) {
                        holder.item.bind(record);
                    }
                    return;
                }
            }
        }
    }

    @Override
    public void onAttachedToRecyclerView(@NonNull RecyclerView rv) {
        super.onAttachedToRecyclerView(rv);
        this.recyclerView = rv;
    }

    @Override
    public void onDetachedFromRecyclerView(@NonNull RecyclerView rv) {
        super.onDetachedFromRecyclerView(rv);
        this.recyclerView = null;
    }

    @Override
    public int getItemViewType(int position) {
        if (position >= displayItems.size()) return TYPE_ADD;
        Object item = displayItems.get(position);
        if (item instanceof TransferRecord) return TYPE_TRANSFER;
        return TYPE_DEVICE;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_TRANSFER) {
            TransferItemView view = new TransferItemView(parent.getContext());
            return new TransferVH(view);
        }
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
        if (getItemViewType(position) == TYPE_TRANSFER) {
            DiscoveredDevice parentDevice = (position > 0 && displayItems.get(position - 1) instanceof DiscoveredDevice)
                    ? (DiscoveredDevice) displayItems.get(position - 1) : null;
            TransferRecord record = (TransferRecord) displayItems.get(position);
            TransferVH vh = (TransferVH) holder;
            vh.item.bind(record);
             if (parentDevice != null) {
                vh.itemView.setOnClickListener(v -> {
                    if (clickListener != null) clickListener.onClick(parentDevice);
                });
                vh.itemView.setOnLongClickListener(v -> {
                    if (clickListener != null) { clickListener.onLongClick(parentDevice); return true; }
                    return false;
                });
            }
            return;
        }
        bindDevice((DeviceVH) holder, (DiscoveredDevice) displayItems.get(position));
    }

    private void bindDevice(DeviceVH vh, DiscoveredDevice device) {
        vh.tvName.setText(device.displayName());
        vh.tvAddr.setText(device.address());
        vh.tvLastJobId.setText(device.lastJobId != null
                ? "lastJobId: " + device.lastJobId : "lastJobId: null");
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
        final TextView tvName, tvAddr, tvLastJobId;
        DeviceVH(@NonNull View v) {
            super(v);
            vOnlineDot = v.findViewById(R.id.vOnlineDot);
            tvName = v.findViewById(R.id.tvDeviceName);
            tvAddr = v.findViewById(R.id.tvDeviceAddr);
            tvLastJobId = v.findViewById(R.id.tvLastJobId);
        }
    }

    static class TransferVH extends RecyclerView.ViewHolder {
        final TransferItemView item;
        TransferVH(TransferItemView v) { super(v); item = v; }
    }
}
