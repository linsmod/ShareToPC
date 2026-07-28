package github.linsmod.sharetopc.ui;

import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.AsyncListDiffer;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import github.linsmod.sharetopc.data.TransferRecord;
import github.linsmod.sharetopc.util.TransferStatusHelper;

/** 传输历史适配器。 */
public class HistoryAdapter extends RecyclerView.Adapter<HistoryAdapter.VH> {

    public interface ActionListener {
        void onRetryRequested(TransferRecord record);
        void onSelectionChanged(int count);
        void onPauseRequested(TransferRecord record);
    }

    private static final DiffUtil.ItemCallback<TransferRecord> DIFF = new DiffUtil.ItemCallback<TransferRecord>() {
        @Override
        public boolean areItemsTheSame(@NonNull TransferRecord old, @NonNull TransferRecord n) {
            return old.id.equals(n.id);
        }
        @Override
        public boolean areContentsTheSame(@NonNull TransferRecord old, @NonNull TransferRecord n) {
            return java.util.Objects.equals(old.status, n.status)
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
        view.bind(record);

        // 多选或状态切换
        view.setOnClickListener(v -> {
            if (selectionMode) {
                int pos = holder.getAdapterPosition();
                if (pos != RecyclerView.NO_POSITION) {
                    toggleSelection(differ.getCurrentList().get(pos), pos);
                }
            }
        });
        view.setOnLongClickListener(v -> {
            if (selectionMode) {
                int pos = holder.getAdapterPosition();
                if (pos != RecyclerView.NO_POSITION) {
                    toggleSelection(differ.getCurrentList().get(pos), pos);
                    return true;
                }
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
