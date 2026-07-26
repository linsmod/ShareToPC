package github.linsmod.sharetopc.ui;

import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.AsyncListDiffer;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import github.linsmod.sharetopc.data.PendingContent;
import github.linsmod.sharetopc.data.TransferRecord;
import github.linsmod.sharetopc.util.FileUtils;
import github.linsmod.sharetopc.util.TransferStatusHelper;

/** 传输历史适配器。 */
public class HistoryAdapter extends RecyclerView.Adapter<HistoryAdapter.VH> {

    public interface ActionListener {
        void onRetry(TransferRecord record);
        void onSendPending(TransferRecord record);
        void onSelectionChanged(int count);
        void onItemClick(TransferRecord record);
        /** 暂停当前传输（点击 SENDING 记录的暂停按钮）。 */
        void onPauseTransfer();
    }

    private static final DiffUtil.ItemCallback<TransferRecord> DIFF = new DiffUtil.ItemCallback<TransferRecord>() {
        @Override
        public boolean areItemsTheSame(@NonNull TransferRecord old, @NonNull TransferRecord n) {
            return old.id.equals(n.id);
        }
        @Override
        public boolean areContentsTheSame(@NonNull TransferRecord old, @NonNull TransferRecord n) {
            return old.success == n.success
                    && java.util.Objects.equals(old.status, n.status)
                    && java.util.Objects.equals(old.host, n.host)
                    && old.port == n.port
                    && java.util.Objects.equals(old.targetName, n.targetName)
                    && java.util.Objects.equals(old.errorMsg, n.errorMsg)
                    && old.sentBytes == n.sentBytes
                    && old.totalBytes == n.totalBytes;
        }
    };

    private final AsyncListDiffer<TransferRecord> differ = new AsyncListDiffer<>(this, DIFF);
    private final Set<String> selectedIds = new HashSet<>();
    private final ActionListener actionListener;
    @Nullable
    private final TransferStatusHelper.PfdChecker pfdChecker;
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault());
    private boolean selectionMode = false;

    public HistoryAdapter(ActionListener actionListener, @Nullable TransferStatusHelper.PfdChecker pfdChecker) {
        this.actionListener = actionListener;
        this.pfdChecker = pfdChecker;
    }

    public void submitList(List<TransferRecord> list) {
        List<TransferRecord> newList = list != null ? list : Collections.emptyList();
        differ.submitList(new ArrayList<>(newList), () -> {
            if (selectionMode) {
                Set<String> alive = new HashSet<>();
                for (TransferRecord r : newList) alive.add(r.id);
                if (selectedIds.retainAll(alive)) notifySelectionChanged();
            }
        });
    }

    public boolean isSelectionMode() { return selectionMode; }
    public List<String> getSelectedIds() { return new ArrayList<>(selectedIds); }

    public List<TransferRecord> getSelectedRecords() {
        List<TransferRecord> result = new ArrayList<>();
        for (TransferRecord r : differ.getCurrentList()) {
            if (selectedIds.contains(r.id)) result.add(r);
        }
        return result;
    }

    public void enterSelectionMode(String firstId) {
        if (selectionMode) return;
        selectionMode = true;
        selectedIds.clear();
        if (firstId != null) selectedIds.add(firstId);
        notifyDataSetChanged();
        notifySelectionChanged();
    }

    public void exitSelectionMode() {
        if (!selectionMode) return;
        selectionMode = false;
        selectedIds.clear();
        notifyDataSetChanged();
        notifySelectionChanged();
    }

    public void toggleSelectAll() {
        if (!selectionMode) return;
        List<TransferRecord> current = differ.getCurrentList();
        if (selectedIds.size() == current.size()) {
            selectedIds.clear();
        } else {
            selectedIds.clear();
            for (TransferRecord r : current) selectedIds.add(r.id);
        }
        notifyDataSetChanged();
        notifySelectionChanged();
    }

    private void toggleSelection(TransferRecord record, int position) {
        if (!selectedIds.remove(record.id)) selectedIds.add(record.id);
        notifyItemChanged(position);
        notifySelectionChanged();
    }

    private void notifySelectionChanged() {
        if (actionListener != null) actionListener.onSelectionChanged(selectedIds.size());
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        TransferItemView item = new TransferItemView(parent.getContext());
        return new VH(item);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        TransferRecord record = differ.getCurrentList().get(position);
        TransferItemView view = holder.item;
        android.content.Context ctx = view.getContext();
        String status = record.statusOrLegacy();

        TransferItemView.BindData data = new TransferItemView.BindData();

        // 文件名（带 [文本] 前缀）
        String prefix = record.contentType == PendingContent.TYPE_TEXT
                ? "[" + ctx.getString(github.linsmod.sharetopc.R.string.type_text) + "] " : "";
        data.name = prefix + record.name;

        // 文件已不存在 → 灰化
        data.nameAlpha = record.isFileGone() ? 0.4f : 1.0f;

        // 状态圆点
        data.status = status;

        // 元信息：状态 · 设备 · 大小 · 时间
        StringBuilder meta = new StringBuilder(TransferItemView.statusLabel(ctx, status));
        String target = record.targetName != null ? record.targetName
                : (record.host != null ? record.host + ":" + record.port : null);
        if (target != null) meta.append(" · ").append(target);
        meta.append(" · ").append(FileUtils.formatSize(record.size))
                .append(" · ").append(timeFormat.format(new Date(record.timeMillis)));
        data.meta = meta.toString();

        // 进度条 + 百分比
        if (record.sentBytes > 0 && record.totalBytes > 0) {
            int pct = (int) (record.sentBytes * 100 / record.totalBytes);
            data.showProgressBar = true;
            data.progress = pct;
            data.percent = pct;
        }

        // 多选框
        data.showCheckbox = selectionMode;
        data.checkboxChecked = selectionMode && selectedIds.contains(record.id);

        // 按钮
        if (selectionMode) {
            // 多选模式：隐藏按钮
            data.actionMode = TransferItemView.ActionMode.NONE;
        } else if (record.isPending()) {
            data.actionMode = TransferItemView.ActionMode.RETRY;
            data.actionLabel = ctx.getString(github.linsmod.sharetopc.R.string.action_send);
            data.actionEnabled = record.canDeliver();
        } else if (TransferRecord.STATUS_SENDING.equals(status)) {
            data.actionMode = TransferItemView.ActionMode.ACTIVE;
        } else if (!record.success) {
            boolean isCancelled = TransferRecord.STATUS_CANCELLED.equals(status);
            data.actionMode = TransferItemView.ActionMode.RETRY;
            data.actionLabel = isCancelled ? ctx.getString(github.linsmod.sharetopc.R.string.action_resume)
                    : ctx.getString(github.linsmod.sharetopc.R.string.retry);
            data.actionEnabled = TransferStatusHelper.canResumeOrRetry(record, pfdChecker);
        }

        view.bind(data);

        // 操作回调
        view.setOnActionListener(new TransferItemView.OnActionListener() {
            @Override public void onPause() {
                if (actionListener != null) actionListener.onPauseTransfer();
            }
            @Override public void onCancel() {
                if (actionListener != null) actionListener.onPauseTransfer();
            }
            @Override public void onResume() {
                if (record.isPending()) {
                    if (actionListener != null) actionListener.onSendPending(record);
                } else {
                    if (actionListener != null) actionListener.onRetry(record);
                }
            }
        });

        // 点击 / 长按
        view.setOnClickListener(v -> {
            if (selectionMode) {
                int pos = holder.getAdapterPosition();
                if (pos != RecyclerView.NO_POSITION) {
                    toggleSelection(differ.getCurrentList().get(pos), pos);
                }
            } else if (actionListener != null) {
                actionListener.onItemClick(record);
            }
        });
        view.setOnLongClickListener(v -> {
            if (!selectionMode) {
                enterSelectionMode(record.id);
                return true;
            }
            return false;
        });
    }

    @Override
    public int getItemCount() { return differ.getCurrentList().size(); }

    static class VH extends RecyclerView.ViewHolder {
        final TransferItemView item;
        VH(TransferItemView v) { super(v); item = v; }
    }
}
