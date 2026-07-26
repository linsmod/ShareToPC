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
import java.util.Objects;

import github.linsmod.sharetopc.R;
import github.linsmod.sharetopc.discovery.DiscoveredDevice;

/** 设备列表适配器（设备 / 传输子项 / 手动添加入口 三种 ViewType）。 */
public class DeviceAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TYPE_DEVICE = 0;
    private static final int TYPE_TRANSFER = 1;
    private static final int TYPE_ADD = 2;

    public static class DeviceTransferInfo {
        public final String deviceKey;
        public final String fileName;
        public final int percent;
        public final boolean active;
        public final boolean paused;
        @Nullable
        public final String recordId;

        public DeviceTransferInfo(String deviceKey, String fileName, int percent, boolean active) {
            this(deviceKey, fileName, percent, active, false, null);
        }

        public DeviceTransferInfo(String deviceKey, String fileName, int percent,
                                  boolean active, boolean paused, @Nullable String recordId) {
            this.deviceKey = deviceKey;
            this.fileName = fileName;
            this.percent = percent;
            this.active = active;
            this.paused = paused;
            this.recordId = recordId;
        }
    }

    public interface OnDeviceClickListener {
        void onClick(DiscoveredDevice device);
        void onLongClick(DiscoveredDevice device);
    }

    public interface OnAddDeviceClickListener { void onAddDevice(); }
    public interface OnCancelTransferListener { void onCancelTransfer(); }
    public interface OnPauseTransferListener { void onPauseTransfer(); }
    public interface OnResumeTransferListener { void onResumeTransfer(@Nullable String recordId); }

    private final List<Object> displayItems = new ArrayList<>();
    private final List<DiscoveredDevice> items = new ArrayList<>();
    private final OnDeviceClickListener clickListener;
    private OnAddDeviceClickListener addClickListener;
    private OnCancelTransferListener cancelListener;
    private OnPauseTransferListener pauseListener;
    private OnResumeTransferListener resumeListener;
    private DeviceTransferInfo currentTransfer;
    private RecyclerView recyclerView;

    public DeviceAdapter(OnDeviceClickListener clickListener) {
        this.clickListener = clickListener;
    }

    public void setOnAddDeviceClickListener(OnAddDeviceClickListener l) { this.addClickListener = l; }
    public void setOnCancelTransferListener(OnCancelTransferListener l) { this.cancelListener = l; }
    public void setOnPauseTransferListener(OnPauseTransferListener l) { this.pauseListener = l; }
    public void setOnResumeTransferListener(OnResumeTransferListener l) { this.resumeListener = l; }

    private void rebuildDisplayItems() {
        displayItems.clear();
        for (DiscoveredDevice device : items) {
            displayItems.add(device);
            if (currentTransfer != null && currentTransfer.deviceKey != null
                    && currentTransfer.deviceKey.equals(device.key())
                    && (currentTransfer.active || currentTransfer.paused)) {
                displayItems.add(currentTransfer);
            }
        }
    }

    public void submitList(List<DiscoveredDevice> list) {
        items.clear();
        if (list != null) items.addAll(list);
        rebuildDisplayItems();
        notifyDataSetChanged();
    }

    public void setCurrentTransfer(@Nullable DeviceTransferInfo info) {
        boolean wasVisible = this.currentTransfer != null
                && (this.currentTransfer.active || this.currentTransfer.paused);
        boolean willBeVisible = info != null && (info.active || info.paused);
        boolean structureChanged = wasVisible != willBeVisible
                || !Objects.equals(
                        this.currentTransfer != null ? this.currentTransfer.deviceKey : null,
                        info != null ? info.deviceKey : null);

        this.currentTransfer = info;

        if (structureChanged) {
            rebuildDisplayItems();
            notifyDataSetChanged();
        } else if (willBeVisible) {
            updateTransferProgressViews(info);
        }
    }

    private void updateTransferProgressViews(DeviceTransferInfo info) {
        if (recyclerView == null) return;
        for (int i = 0; i < displayItems.size(); i++) {
            if (displayItems.get(i) instanceof DeviceTransferInfo) {
                TransferVH holder = (TransferVH) recyclerView.findViewHolderForAdapterPosition(i);
                if (holder != null) {
                    bindTransferContent(holder.item, info);
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
            TransferItemView view = new TransferItemView(parent.getContext());
            // 传输子项：隐藏状态圆点、多选框、元信息
            view.setStatusDotVisible(false);
            view.setMetaVisible(false);
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
            DeviceTransferInfo info = (DeviceTransferInfo) displayItems.get(position);
            TransferVH vh = (TransferVH) holder;
            bindTransferContent(vh.item, info);
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
        vh.tvAddr.setText(device.host + ":" + device.port);
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

    private void bindTransferContent(TransferItemView view, DeviceTransferInfo info) {
        android.content.Context ctx = view.getContext();

        if (info.paused) {
            view.setName(info.fileName + "  " + ctx.getString(R.string.state_paused));
            view.setPercent(-1);
            view.setProgressBarVisible(false);
            view.setActionMode(TransferItemView.ActionMode.RETRY,
                    ctx.getString(R.string.action_resume), true);
        } else {
            view.setName(info.fileName + "  " + ctx.getString(R.string.state_sending));
            view.setPercent(info.percent >= 0 ? info.percent : -1);
            view.setProgressBarVisible(true);
            view.setProgress(info.percent);
            view.setActionMode(TransferItemView.ActionMode.ACTIVE, null, false);
        }

        view.setOnActionListener(new TransferItemView.OnActionListener() {
            @Override public void onPause() {
                if (pauseListener != null) pauseListener.onPauseTransfer();
            }
            @Override public void onCancel() {
                if (cancelListener != null) cancelListener.onCancelTransfer();
            }
            @Override public void onResume() {
                if (resumeListener != null) resumeListener.onResumeTransfer(info.recordId);
            }
        });
    }

    @Override
    public int getItemCount() { return displayItems.size() + 1; }

    // ── ViewHolders ──────────────────────────────────────

    static class DeviceVH extends RecyclerView.ViewHolder {
        final View vOnlineDot;
        final TextView tvName, tvAddr;
        DeviceVH(@NonNull View v) {
            super(v);
            vOnlineDot = v.findViewById(R.id.vOnlineDot);
            tvName = v.findViewById(R.id.tvDeviceName);
            tvAddr = v.findViewById(R.id.tvDeviceAddr);
        }
    }

    static class TransferVH extends RecyclerView.ViewHolder {
        final TransferItemView item;
        TransferVH(TransferItemView v) { super(v); item = v; }
    }
}
