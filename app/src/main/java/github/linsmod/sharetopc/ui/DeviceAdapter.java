package github.linsmod.sharetopc.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import github.linsmod.sharetopc.R;
import github.linsmod.sharetopc.discovery.DiscoveredDevice;

/** 设备列表适配器（支持设备/传输子项/手动添加入口三种 ViewType）。 */
public class DeviceAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TYPE_DEVICE = 0;
    private static final int TYPE_TRANSFER = 1;
    private static final int TYPE_ADD = 2;

    /** 当前设备传输状态。 */
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

    /** "手动添加" 条目点击回调。 */
    public interface OnAddDeviceClickListener {
        void onAddDevice();
    }

    /** 取消传输回调。 */
    public interface OnCancelTransferListener {
        void onCancelTransfer();
    }

    /** 展示列表：设备与传输子项混合排列。 */
    private final List<Object> displayItems = new ArrayList<>();
    private final List<DiscoveredDevice> items = new ArrayList<>();
    private final OnDeviceClickListener clickListener;
    private OnAddDeviceClickListener addClickListener;
    private OnCancelTransferListener cancelListener;
    private DeviceTransferInfo currentTransfer;
    private RecyclerView recyclerView;

    public DeviceAdapter(OnDeviceClickListener clickListener) {
        this.clickListener = clickListener;
    }

    public void setOnAddDeviceClickListener(OnAddDeviceClickListener listener) {
        this.addClickListener = listener;
    }

    public void setOnCancelTransferListener(OnCancelTransferListener listener) {
        this.cancelListener = listener;
    }

    /** 重建 displayItems：设备 + 传输子项混合排列。 */
    private void rebuildDisplayItems() {
        displayItems.clear();
        for (DiscoveredDevice device : items) {
            displayItems.add(device);
            if (currentTransfer != null && currentTransfer.active
                    && currentTransfer.deviceKey != null
                    && currentTransfer.deviceKey.equals(device.key())) {
                displayItems.add(currentTransfer);
            }
        }
    }

    public void submitList(List<DiscoveredDevice> list) {
        items.clear();
        if (list != null) {
            items.addAll(list);
        }
        rebuildDisplayItems();
        notifyDataSetChanged();
    }

    public void setCurrentTransfer(@Nullable DeviceTransferInfo info) {
        boolean wasActive = this.currentTransfer != null && this.currentTransfer.active;
        boolean willBeActive = info != null && info.active;
        boolean structureChanged = wasActive != willBeActive
                || !Objects.equals(
                        this.currentTransfer != null ? this.currentTransfer.deviceKey : null,
                        info != null ? info.deviceKey : null);

        this.currentTransfer = info;

        if (structureChanged) {
            // 传输子项插入/移除/换设备 → 必须全量重建
            rebuildDisplayItems();
            notifyDataSetChanged();
        } else if (willBeActive) {
            // 同一设备，仅进度/文件名变化 → 直接更新 ViewHolder，不触发 onBindViewHolder
            updateTransferProgressViews(info);
        }
    }

    /** 找到传输子项 ViewHolder 并直接更新进度，不走 onBindViewHolder。 */
    private void updateTransferProgressViews(DeviceTransferInfo info) {
        if (recyclerView == null) return;
        for (int i = 0; i < displayItems.size(); i++) {
            if (displayItems.get(i) instanceof DeviceTransferInfo) {
                TransferVH holder = (TransferVH) recyclerView.findViewHolderForAdapterPosition(i);
                if (holder != null) {
                    holder.tvFileName.setText(info.fileName + "  发送中…");
                    holder.tvPercent.setText(info.percent >= 0 ? info.percent + "%" : "");
                    holder.progressBar.setProgress(Math.max(0, info.percent));
                }
                return;
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
        if (item instanceof DeviceTransferInfo) return TYPE_TRANSFER;
        return TYPE_DEVICE;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_TRANSFER) {
            View view = inflater.inflate(R.layout.item_transfer_sub, parent, false);
            return new TransferVH(view);
        }
        if (viewType == TYPE_ADD) {
            View view = inflater.inflate(R.layout.item_add_device, parent, false);
            return new RecyclerView.ViewHolder(view) {};
        }
        View view = inflater.inflate(R.layout.item_device, parent, false);
        return new DeviceVH(view);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (getItemViewType(position) == TYPE_ADD) {
            holder.itemView.setOnClickListener(v -> {
                if (addClickListener != null) {
                    addClickListener.onAddDevice();
                }
            });
            return;
        }
        if (getItemViewType(position) == TYPE_TRANSFER) {
            // 传输子项的父设备一定是前一个 displayItem
            DiscoveredDevice parentDevice = (position > 0 && displayItems.get(position - 1) instanceof DiscoveredDevice)
                    ? (DiscoveredDevice) displayItems.get(position - 1) : null;
            bindTransfer((TransferVH) holder, (DeviceTransferInfo) displayItems.get(position), parentDevice);
            return;
        }
        bindDevice((DeviceVH) holder, (DiscoveredDevice) displayItems.get(position));
    }

    private void bindDevice(DeviceVH vh, DiscoveredDevice device) {
        vh.tvName.setText(device.displayName());
        vh.tvAddr.setText(device.host + ":" + device.port);
        vh.vOnlineDot.setBackgroundResource(
                device.isOnline ? R.drawable.dot_online : R.drawable.dot_offline);

        vh.itemView.setOnClickListener(v -> {
            if (clickListener != null) {
                clickListener.onClick(device);
            }
        });
        vh.itemView.setOnLongClickListener(v -> {
            if (clickListener != null) {
                clickListener.onLongClick(device);
                return true;
            }
            return false;
        });
    }

    private void bindTransfer(TransferVH vh, DeviceTransferInfo info, DiscoveredDevice parentDevice) {
        vh.tvFileName.setText(info.fileName + "  发送中…");
        vh.tvPercent.setText(info.percent >= 0 ? info.percent + "%" : "");
        vh.progressBar.setProgress(Math.max(0, info.percent));
        vh.btnCancel.setOnClickListener(v -> {
            if (cancelListener != null) {
                cancelListener.onCancelTransfer();
            }
        });
        // 点击 sub item → 执行父设备的点击逻辑
        if (parentDevice != null) {
            vh.itemView.setOnClickListener(v -> {
                if (clickListener != null) {
                    clickListener.onClick(parentDevice);
                }
            });
            vh.itemView.setOnLongClickListener(v -> {
                if (clickListener != null) {
                    clickListener.onLongClick(parentDevice);
                    return true;
                }
                return false;
            });
        }
    }

    @Override
    public int getItemCount() {
        return displayItems.size() + 1; // +1: "手动添加" 入口
    }

    // ---- ViewHolders ----

    static class DeviceVH extends RecyclerView.ViewHolder {
        final View vOnlineDot;
        final TextView tvName;
        final TextView tvAddr;

        DeviceVH(@NonNull View itemView) {
            super(itemView);
            vOnlineDot = itemView.findViewById(R.id.vOnlineDot);
            tvName = itemView.findViewById(R.id.tvDeviceName);
            tvAddr = itemView.findViewById(R.id.tvDeviceAddr);
        }
    }

    static class TransferVH extends RecyclerView.ViewHolder {
        final TextView tvFileName;
        final TextView tvPercent;
        final ProgressBar progressBar;
        final ImageButton btnCancel;

        TransferVH(@NonNull View itemView) {
            super(itemView);
            tvFileName = itemView.findViewById(R.id.tvTransferFileName);
            tvPercent = itemView.findViewById(R.id.tvTransferPercent);
            progressBar = itemView.findViewById(R.id.progressTransferSub);
            btnCancel = itemView.findViewById(R.id.btnCancelTransferSub);
        }
    }
}
