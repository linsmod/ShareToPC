package github.linsmod.sharetopc.ui;

import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import github.linsmod.sharetopc.util.FileUtils;

/** 设备任务队列适配器。 */
public class DeviceTaskAdapter extends RecyclerView.Adapter<DeviceTaskAdapter.VH> {

    public static final int STATUS_TRANSFERRING = 1;
    public static final int STATUS_SUCCESS = 2;
    public static final int STATUS_FAILED = 3;
    public static final int STATUS_CANCELLED = 4;

    public interface TaskActionListener {
        void onPauseTransfer(String taskId);
        void onCancelTransfer(String taskId);
        void onResumeOrRetry(TaskItem item);
    }

    public static class TaskItem {
        public final String id;
        public final String name;
        public final long size;
        public final long time;
        public final int status;
        public final int percent;   // -1 无进度
        public final boolean isCurrent;
        /** 重试/继续按钮是否可用（内容仍存在 + PFD 仍有效）。 */
        public final boolean canResume;

        public TaskItem(String id, String name, long size, long time,
                        int status, int percent, boolean isCurrent) {
            this(id, name, size, time, status, percent, isCurrent, true);
        }

        public TaskItem(String id, String name, long size, long time,
                        int status, int percent, boolean isCurrent, boolean canResume) {
            this.id = id;
            this.name = name;
            this.size = size;
            this.time = time;
            this.status = status;
            this.percent = percent;
            this.isCurrent = isCurrent;
            this.canResume = canResume;
        }

        private String statusString() {
            switch (status) {
                case STATUS_TRANSFERRING: return "SENDING";
                case STATUS_SUCCESS:      return "SUCCESS";
                case STATUS_FAILED:       return "FAILED";
                case STATUS_CANCELLED:    return "CANCELLED";
                default:                  return "FAILED";
            }
        }
    }

    private final List<TaskItem> items = new ArrayList<>();
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
    private RecyclerView recyclerView;

    @androidx.annotation.Nullable
    private TaskActionListener actionListener;

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

    public void setTaskActionListener(@androidx.annotation.Nullable TaskActionListener l) {
        this.actionListener = l;
    }

    public void submitList(List<TaskItem> list) {
        List<TaskItem> newList = list != null ? list : new ArrayList<>();
        if (items.isEmpty() || newList.isEmpty()) {
            items.clear();
            items.addAll(newList);
            notifyDataSetChanged();
            return;
        }
        DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new DiffCallback(items, newList));
        items.clear();
        items.addAll(newList);
        diff.dispatchUpdatesTo(this);
    }

    private static class DiffCallback extends DiffUtil.Callback {
        private final List<TaskItem> oldList, newList;
        DiffCallback(List<TaskItem> o, List<TaskItem> n) { oldList = o; newList = n; }
        @Override public int getOldListSize() { return oldList.size(); }
        @Override public int getNewListSize() { return newList.size(); }
        @Override public boolean areItemsTheSame(int o, int n) {
            return oldList.get(o).id.equals(newList.get(n).id);
        }
        @Override public boolean areContentsTheSame(int o, int n) {
            TaskItem a = oldList.get(o), b = newList.get(n);
            return a.status == b.status && a.percent == b.percent
                    && a.isCurrent == b.isCurrent && a.canResume == b.canResume
                    && a.name.equals(b.name);
        }
    }

    /** 直接操作 ViewHolder 更新进度，不走 onBindViewHolder。 */
    public void updateTransferProgress(int percent) {
        if (recyclerView == null) return;
        for (int i = 0; i < items.size(); i++) {
            TaskItem item = items.get(i);
            if (item.isCurrent && item.status == STATUS_TRANSFERRING) {
                items.set(i, new TaskItem(item.id, item.name, item.size, item.time,
                        item.status, percent, item.isCurrent));
                VH holder = (VH) recyclerView.findViewHolderForAdapterPosition(i);
                if (holder != null) {
                    holder.item.setPercent(percent);
                }
                return;
            }
        }
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        TransferItemView view = new TransferItemView(parent.getContext());
        view.setProgressBarVisible(false); // 任务弹窗不显示进度条
        view.setStatusDotVisible(true);
        view.setMetaVisible(true);
        return new VH(view);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        TaskItem item = items.get(position);
        TransferItemView view = holder.item;
        android.content.Context ctx = view.getContext();
        String statusStr = item.statusString();

        view.setName(item.name);
        view.setTransferStatus(statusStr);

        // 元信息
        StringBuilder meta = new StringBuilder(TransferItemView.statusLabel(ctx, statusStr));
        meta.append(" · ").append(FileUtils.formatSize(item.size))
                .append(" · ").append(timeFormat.format(new Date(item.time)));
        view.setMeta(meta.toString());

        // 百分比
        if (item.isCurrent && item.percent >= 0) {
            view.setPercent(item.percent);
        } else {
            view.setPercent(-1); // 隐藏
        }

        // 隐藏多选框
        view.setCheckboxVisible(false, false);

        // 按钮
        if (item.status == STATUS_TRANSFERRING) {
            view.setActionMode(TransferItemView.ActionMode.ACTIVE, null, false);
        } else if (item.status == STATUS_FAILED || item.status == STATUS_CANCELLED) {
            String label = item.status == STATUS_CANCELLED
                    ? ctx.getString(github.linsmod.sharetopc.R.string.action_resume)
                    : ctx.getString(github.linsmod.sharetopc.R.string.retry);
            view.setActionMode(TransferItemView.ActionMode.RETRY, label, item.canResume);
        } else {
            view.setActionMode(TransferItemView.ActionMode.NONE, null, false);
        }

        // 操作回调
        view.setOnActionListener(new TransferItemView.OnActionListener() {
            @Override public void onPause() {
                if (actionListener != null) actionListener.onPauseTransfer(item.id);
            }
            @Override public void onCancel() {
                if (actionListener != null) actionListener.onCancelTransfer(item.id);
            }
            @Override public void onResume() {
                if (actionListener != null) actionListener.onResumeOrRetry(item);
            }
        });
    }

    @Override
    public int getItemCount() { return items.size(); }

    static class VH extends RecyclerView.ViewHolder {
        final TransferItemView item;
        VH(TransferItemView v) { super(v); item = v; }
    }
}
