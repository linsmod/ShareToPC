package github.linsmod.sharetopc.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import github.linsmod.sharetopc.R;
import github.linsmod.sharetopc.util.FileUtils;

/** 设备任务队列适配器。展示排队中/传输中/已完成的混合列表。 */
public class DeviceTaskAdapter extends RecyclerView.Adapter<DeviceTaskAdapter.VH> {

    /** 任务状态类型。 */
    public static final int STATUS_TRANSFERRING = 1; // 传输中
    public static final int STATUS_SUCCESS = 2;      // 成功
    public static final int STATUS_FAILED = 3;       // 失败
    public static final int STATUS_CANCELLED = 4;    // 已取消

    /** 统一的任务展示模型。 */
    public static class TaskItem {
        public final String id;
        public final String name;
        public final long size;
        public final long time;
        public final int status; // STATUS_*
        public final int percent; // -1 表示无进度
        public final boolean isCurrent; // 是否正在传输中（展示 progressBar）

        public TaskItem(String id, String name, long size, long time,
                        int status, int percent, boolean isCurrent) {
            this.id = id;
            this.name = name;
            this.size = size;
            this.time = time;
            this.status = status;
            this.percent = percent;
            this.isCurrent = isCurrent;
        }
    }

    private final List<TaskItem> items = new ArrayList<>();
    private final SimpleDateFormat timeFormat =
            new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
    private RecyclerView recyclerView;

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

    public void submitList(List<TaskItem> list) {
        List<TaskItem> newList = list != null ? list : new ArrayList<>();
        // 有一方为空时跳过 DiffUtil 动画，避免从空列表切到有数据时逐项插入的闪烁
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

    /** DiffUtil 回调：按 id 匹配，按 status/percent 判断内容变化。 */
    private static class DiffCallback extends DiffUtil.Callback {
        private final List<TaskItem> oldList;
        private final List<TaskItem> newList;

        DiffCallback(List<TaskItem> oldList, List<TaskItem> newList) {
            this.oldList = oldList;
            this.newList = newList;
        }

        @Override
        public int getOldListSize() { return oldList.size(); }

        @Override
        public int getNewListSize() { return newList.size(); }

        @Override
        public boolean areItemsTheSame(int oldItemPosition, int newItemPosition) {
            return oldList.get(oldItemPosition).id.equals(newList.get(newItemPosition).id);
        }

        @Override
        public boolean areContentsTheSame(int oldItemPosition, int newItemPosition) {
            TaskItem o = oldList.get(oldItemPosition);
            TaskItem n = newList.get(newItemPosition);
            return o.status == n.status && o.percent == n.percent
                    && o.isCurrent == n.isCurrent && o.name.equals(n.name);
        }
    }

    /** 直接操作 ViewHolder 更新进度百分比，不走 onBindViewHolder。 */
    public void updateTransferProgress(int percent) {
        if (recyclerView == null) return;
        for (int i = 0; i < items.size(); i++) {
            TaskItem item = items.get(i);
            if (item.isCurrent && item.status == STATUS_TRANSFERRING) {
                // 更新数据源（供滚动回屏时 onBindViewHolder 读取）
                items.set(i, new TaskItem(item.id, item.name, item.size, item.time,
                        item.status, percent, item.isCurrent));
                // 直接操作可见的 ViewHolder
                VH holder = (VH) recyclerView.findViewHolderForAdapterPosition(i);
                if (holder != null) {
                    android.content.Context ctx = holder.itemView.getContext();
                    holder.tvPercent.setVisibility(View.VISIBLE);
                    holder.tvPercent.setText(percent + "%");
                    holder.tvPercent.setTextColor(percent >= 100
                            ? ctx.getColor(android.R.color.holo_green_dark)
                            : ctx.getColor(android.R.color.holo_blue_dark));
                }
                return;
            }
        }
    }

    public int getCount() {
        return items.size();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_device_task, parent, false);
        return new VH(view);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        TaskItem item = items.get(position);
        android.content.Context ctx = holder.itemView.getContext();

        holder.tvName.setText(item.name);

        // 状态文案
        String statusText;
        switch (item.status) {
            case STATUS_TRANSFERRING:
                statusText = ctx.getString(R.string.queue_status_transferring);
                break;
            case STATUS_SUCCESS:
                statusText = ctx.getString(R.string.result_ok);
                break;
            case STATUS_FAILED:
                statusText = ctx.getString(R.string.result_failed);
                break;
            case STATUS_CANCELLED:
                statusText = ctx.getString(R.string.result_cancelled);
                break;
            default:
                statusText = "";
        }

        StringBuilder meta = new StringBuilder(statusText);
        meta.append(" · ").append(FileUtils.formatSize(item.size))
                .append(" · ").append(timeFormat.format(new Date(item.time)));
        holder.tvMeta.setText(meta.toString());

        // 状态圆点颜色
        int dotColor;
        switch (item.status) {
            case STATUS_TRANSFERRING:
                dotColor = ctx.getColor(android.R.color.holo_blue_light);
                break;
            case STATUS_SUCCESS:
                dotColor = ctx.getColor(android.R.color.holo_green_dark);
                break;
            case STATUS_FAILED:
                dotColor = ctx.getColor(android.R.color.holo_red_dark);
                break;
            case STATUS_CANCELLED:
                dotColor = ctx.getColor(android.R.color.darker_gray);
                break;
            default:
                dotColor = ctx.getColor(android.R.color.darker_gray);
        }
        holder.vStatus.setBackgroundColor(dotColor);

        // 进度百分比
        if (item.isCurrent && item.percent >= 0) {
            holder.tvPercent.setVisibility(View.VISIBLE);
            holder.tvPercent.setText(item.percent + "%");
            int textColor;
            if (item.percent >= 100) {
                textColor = ctx.getColor(android.R.color.holo_green_dark);
            } else {
                textColor = ctx.getColor(android.R.color.holo_blue_dark);
            }
            holder.tvPercent.setTextColor(textColor);
        } else {
            holder.tvPercent.setVisibility(View.GONE);
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final View vStatus;
        final TextView tvName;
        final TextView tvMeta;
        final TextView tvPercent;

        VH(@NonNull View itemView) {
            super(itemView);
            vStatus = itemView.findViewById(R.id.vTaskStatus);
            tvName = itemView.findViewById(R.id.tvTaskName);
            tvMeta = itemView.findViewById(R.id.tvTaskMeta);
            tvPercent = itemView.findViewById(R.id.tvTaskPercent);
        }
    }
}
