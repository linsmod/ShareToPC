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
        /** 统一状态：SENDING | PAUSED | SUCCESS | FAILED | CANCELLED */
        public final String status;
        public final long size;
        public final long time;
        public final int contentType;
        @Nullable
        public final String recordId;

        public DeviceTransferInfo(String deviceKey, String fileName, int percent, String status) {
            this(deviceKey, fileName, percent, status, 0, 0, 0, null);
        }

        public DeviceTransferInfo(String deviceKey, String fileName, int percent,
                                  String status, @Nullable String recordId) {
            this(deviceKey, fileName, percent, status, 0, 0, 0, recordId);
        }

        public DeviceTransferInfo(String deviceKey, String fileName, int percent,
                                  String status, long size, long time,
                                  int contentType, @Nullable String recordId) {
            this.deviceKey = deviceKey;
            this.fileName = fileName;
            this.percent = percent;
            this.status = status;
            this.size = size;
            this.time = time;
            this.contentType = contentType;
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
                    && currentTransfer.deviceKey.equals(device.key())) {
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
        // 忽略 null，不清空已显示的传输项（始终显示最近一次传输状态）
        if (info == null) return;

        String oldKey = this.currentTransfer != null ? this.currentTransfer.deviceKey : null;
        String newKey = info.deviceKey;
        boolean structureChanged = !Objects.equals(oldKey, newKey);

        this.currentTransfer = info;

        if (structureChanged) {
            rebuildDisplayItems();
            notifyDataSetChanged();
        } else {
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

        TransferItemView.BindData data = new TransferItemView.BindData();

        // 文件名（带 [文本] 前缀）
        String prefix = info.contentType == github.linsmod.sharetopc.data.PendingContent.TYPE_TEXT
                ? "[" + ctx.getString(R.string.type_text) + "] " : "";
        data.name = prefix + (info.fileName != null ? info.fileName : "");

        // 状态圆点 + 按钮区（单一 status 驱动，不再用 active/paused 拼凑）
        String status = info.status != null ? info.status : "SUCCESS";
        data.status = status;
        switch (status) {
            case "SENDING":
            case "CONNECTING":
                data.actionMode = TransferItemView.ActionMode.ACTIVE;
                break;
            case "PAUSED":
                data.actionMode = TransferItemView.ActionMode.RETRY;
                data.actionLabel = ctx.getString(R.string.action_resume);
                data.actionEnabled = true;
                break;
            case "FAILED":
            case "CANCELLED":
                data.actionMode = TransferItemView.ActionMode.RETRY;
                data.actionLabel = ctx.getString(R.string.retry);
                data.actionEnabled = true;
                break;
            default: // SUCCESS
                break;
        }

        // 元信息：状态 · 大小 · 时间（与 HistoryAdapter 格式一致）
        StringBuilder meta = new StringBuilder(TransferItemView.statusLabel(ctx, status));
        if (info.size > 0) {
            meta.append(" · ").append(github.linsmod.sharetopc.util.FileUtils.formatSize(info.size));
        }
        if (info.time > 0) {
            java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault());
            meta.append(" · ").append(fmt.format(new java.util.Date(info.time)));
        }
        data.meta = meta.toString();

        // 百分比 + 进度条
        boolean hasProgress = info.percent >= 0;
        data.percent = hasProgress ? info.percent : -1;
        data.showProgressBar = hasProgress;
        data.progress = hasProgress ? info.percent : 0;

        view.bind(data);

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
