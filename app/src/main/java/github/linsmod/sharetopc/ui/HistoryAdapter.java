package github.linsmod.sharetopc.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import github.linsmod.sharetopc.R;
import github.linsmod.sharetopc.data.PendingContent;
import github.linsmod.sharetopc.data.TransferRecord;
import github.linsmod.sharetopc.util.FileUtils;

/** 传输历史适配器。支持 成功/失败(重试)/暂存(补发) 三种状态，以及长按进入的多选管理模式。 */
public class HistoryAdapter extends RecyclerView.Adapter<HistoryAdapter.VH> {

    public interface ActionListener {
        /** 失败记录重发。 */
        void onRetry(TransferRecord record);

        /** 暂存记录选择设备补发。 */
        void onSendPending(TransferRecord record);

        /** 多选状态变化（count 为已选数量；配合 isSelectionMode 判断是否处于选择模式）。 */
        void onSelectionChanged(int count);

        /** 非多选状态下点击条目（用于文本预览等）。 */
        void onItemClick(TransferRecord record);
    }

    private final List<TransferRecord> items = new ArrayList<>();
    private final Set<String> selectedIds = new HashSet<>();
    private final ActionListener actionListener;
    private final SimpleDateFormat timeFormat =
            new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault());
    private boolean selectionMode = false;

    public HistoryAdapter(ActionListener actionListener) {
        this.actionListener = actionListener;
    }

    public void submitList(List<TransferRecord> list) {
        items.clear();
        if (list != null) {
            items.addAll(list);
        }
        if (selectionMode) {
            Set<String> alive = new HashSet<>();
            for (TransferRecord r : items) {
                alive.add(r.id);
            }
            if (selectedIds.retainAll(alive)) {
                notifySelectionChanged();
            }
        }
        notifyDataSetChanged();
    }

    public boolean isSelectionMode() {
        return selectionMode;
    }

    public List<String> getSelectedIds() {
        return new ArrayList<>(selectedIds);
    }

    /** 当前选中的记录对象（保持列表顺序）。 */
    public List<TransferRecord> getSelectedRecords() {
        List<TransferRecord> result = new ArrayList<>();
        for (TransferRecord r : items) {
            if (selectedIds.contains(r.id)) {
                result.add(r);
            }
        }
        return result;
    }

    /** 长按进入多选模式，并选中触发项。 */
    public void enterSelectionMode(String firstId) {
        if (selectionMode) {
            return;
        }
        selectionMode = true;
        selectedIds.clear();
        if (firstId != null) {
            selectedIds.add(firstId);
        }
        notifyDataSetChanged();
        notifySelectionChanged();
    }

    public void exitSelectionMode() {
        if (!selectionMode) {
            return;
        }
        selectionMode = false;
        selectedIds.clear();
        notifyDataSetChanged();
        notifySelectionChanged();
    }

    /** 全选 / 取消全选。 */
    public void toggleSelectAll() {
        if (!selectionMode) {
            return;
        }
        if (selectedIds.size() == items.size()) {
            selectedIds.clear();
        } else {
            selectedIds.clear();
            for (TransferRecord r : items) {
                selectedIds.add(r.id);
            }
        }
        notifyDataSetChanged();
        notifySelectionChanged();
    }

    private void toggleSelection(TransferRecord record, int position) {
        if (!selectedIds.remove(record.id)) {
            selectedIds.add(record.id);
        }
        notifyItemChanged(position);
        notifySelectionChanged();
    }

    private void notifySelectionChanged() {
        if (actionListener != null) {
            actionListener.onSelectionChanged(selectedIds.size());
        }
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_history, parent, false);
        return new VH(view);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        TransferRecord record = items.get(position);
        android.content.Context ctx = holder.itemView.getContext();

        String prefix = record.contentType == PendingContent.TYPE_TEXT
                ? "[" + ctx.getString(R.string.type_text) + "] "
                : "";
        holder.tvName.setText(prefix + record.name);

        // 文件已不存在：灰化
        boolean fileGone = record.isFileGone();
        holder.tvName.setAlpha(fileGone ? 0.4f : 1.0f);
        holder.tvMeta.setAlpha(fileGone ? 0.4f : 1.0f);

        String status = statusText(ctx, record);
        StringBuilder meta = new StringBuilder(status);
        String target = record.targetName != null ? record.targetName
                : (record.host != null ? record.host + ":" + record.port : null);
        if (target != null) {
            meta.append(" · ").append(target);
        }
        meta.append(" · ").append(FileUtils.formatSize(record.size))
                .append(" · ").append(timeFormat.format(new Date(record.timeMillis)));
        holder.tvMeta.setText(meta.toString());

        // 多选模式：显示勾选框，隐藏重试/发送按钮
        holder.cbSelect.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
        holder.cbSelect.setChecked(selectedIds.contains(record.id));

        if (!selectionMode && record.isPending()) {
            bindAction(holder.btnRetry, ctx.getString(R.string.action_send),
                    record.canDeliver(), () -> actionListener.onSendPending(record));
        } else if (!selectionMode && !record.success) {
            bindAction(holder.btnRetry, ctx.getString(R.string.retry),
                    record.canDeliver(), () -> actionListener.onRetry(record));
        } else {
            holder.btnRetry.setVisibility(View.GONE);
            holder.btnRetry.setOnClickListener(null);
        }

        holder.itemView.setOnClickListener(v -> {
            if (selectionMode) {
                int pos = holder.getAdapterPosition();
                if (pos != RecyclerView.NO_POSITION) {
                    toggleSelection(items.get(pos), pos);
                }
            } else if (actionListener != null) {
                actionListener.onItemClick(record);
            }
        });
        holder.itemView.setOnLongClickListener(v -> {
            if (!selectionMode) {
                enterSelectionMode(record.id);
                return true;
            }
            return false;
        });
    }

    private void bindAction(Button button, String text, boolean enabled, Runnable action) {
        button.setText(text);
        button.setVisibility(View.VISIBLE);
        button.setEnabled(enabled);
        button.setOnClickListener(v -> {
            if (actionListener != null) {
                action.run();
            }
        });
    }

    private String statusText(android.content.Context ctx, TransferRecord record) {
        switch (record.statusOrLegacy()) {
            case TransferRecord.STATUS_SUCCESS:
                return ctx.getString(R.string.result_ok);
            case TransferRecord.STATUS_PENDING:
                return ctx.getString(R.string.result_pending);
            default:
                return ctx.getString(R.string.result_failed);
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final CheckBox cbSelect;
        final TextView tvName;
        final TextView tvMeta;
        final Button btnRetry;

        VH(@NonNull View itemView) {
            super(itemView);
            cbSelect = itemView.findViewById(R.id.cbSelect);
            tvName = itemView.findViewById(R.id.tvHistoryName);
            tvMeta = itemView.findViewById(R.id.tvHistoryMeta);
            btnRetry = itemView.findViewById(R.id.btnRetry);
        }
    }
}
