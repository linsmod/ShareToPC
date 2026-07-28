package github.linsmod.sharetopc.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import github.linsmod.sharetopc.R;
import github.linsmod.sharetopc.data.TransferRecord;
import github.linsmod.sharetopc.util.TransferStatusHelper;

/**
 * 传输任务树形适配器：分组标题 + 可展开子项。
 * 数据源为 List&lt;TaskGroup&gt;，扁平化为 [Header, Entry*] 交替列表。
 */
public class TaskAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TYPE_GROUP = 0;
    private static final int TYPE_ENTRY = 1;

    public interface ActionListener {
        void onRetryRequested(TransferRecord record);
        void onPauseRequested(TransferRecord record);
    }

    private final List<TaskGroup> groups = new ArrayList<>();
    /** 扁平化后的 DisplayItem 列表。 */
    private final List<DisplayItem> displayList = new ArrayList<>();
    private final ActionListener actionListener;
    @Nullable
    private final TransferStatusHelper.PfdChecker pfdChecker;
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault());

    public TaskAdapter(ActionListener actionListener, @Nullable TransferStatusHelper.PfdChecker pfdChecker) {
        this.actionListener = actionListener;
        this.pfdChecker = pfdChecker;
    }

    @SuppressWarnings("unused")
    private static final boolean ENABLE_GROUP_BACKGROUND = true;

    // ── 扁平化内部模型 ──────────────────────────────────

    private static class DisplayItem {
        final int type;          // TYPE_GROUP / TYPE_ENTRY
        final int groupIndex;    // 所属 groups 下标
        final TransferRecord entry; // TYPE_ENTRY 时非 null
        final TaskGroup group;   // TYPE_GROUP 时非 null

        DisplayItem(int type, int groupIndex, TransferRecord entry, TaskGroup group) {
            this.type = type;
            this.groupIndex = groupIndex;
            this.entry = entry;
            this.group = group;
        }
    }

    // ── 公开方法 ────────────────────────────────────────

    public void submitList(List<TaskGroup> list) {
        groups.clear();
        if (list != null) groups.addAll(list);

        rebuildDisplayList();
        notifyDataSetChanged();
    }

    // ── 扁平化重建 ──────────────────────────────────────

    private void rebuildDisplayList() {
        displayList.clear();
        for (int i = 0; i < groups.size(); i++) {
            TaskGroup g = groups.get(i);
            displayList.add(new DisplayItem(TYPE_GROUP, i, null, g));
            if (g.expanded) {
                for (TransferRecord r : g.entries) {
                    displayList.add(new DisplayItem(TYPE_ENTRY, i, r, g));
                }
            }
        }
    }

    // ── Adapter ─────────────────────────────────────────

    @Override
    public int getItemViewType(int position) {
        return displayList.get(position).type;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_GROUP) {
            return new GroupVH(inflater.inflate(R.layout.item_task_group, parent, false));
        }
        TransferItemView item = new TransferItemView(parent.getContext());
        return new EntryVH(item);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        DisplayItem di = displayList.get(position);
        if (di.type == TYPE_GROUP) {
            bindGroup((GroupVH) holder, di);
        } else {
            bindEntry((EntryVH) holder, di);
        }
    }

    @Override
    public int getItemCount() { return displayList.size(); }

    // ── 分组绑定 ────────────────────────────────────────

    private void bindGroup(GroupVH vh, DisplayItem di) {
        TaskGroup g = di.group;
        Context ctx = vh.itemView.getContext();

        // 分组名 = friendly time
        if (g.timeMillis > 0) {
            vh.tvDevice.setText(friendlyTime(g.timeMillis));
        } else {
            vh.tvDevice.setText("");
        }

        // 展开/折叠箭头
        vh.tvArrow.setText(g.expanded ? "▼" : "▶");

        // 子项计数
        vh.tvCount.setText(g.completedEntries + "/" + g.totalEntries);

        // 副标题 = 设备名（从设备跳转时不需要，弹窗标题已有）
        if (g.showDeviceInfo) {
            String label = g.deviceName != null && !g.deviceName.isEmpty()
                    ? g.deviceName : g.deviceAddress();
            if (label != null && !label.isEmpty()) {
                vh.tvTime.setText(label);
                vh.tvTime.setVisibility(View.VISIBLE);
            } else {
                vh.tvTime.setVisibility(View.GONE);
            }
        } else {
            vh.tvTime.setVisibility(View.GONE);
        }

        // 点击切换展开
        vh.itemView.setOnClickListener(v -> {
            g.expanded = !g.expanded;
            rebuildDisplayList();
            notifyDataSetChanged();
        });
    }

    // ── 条目绑定 ────────────────────────────────────────

    private void bindEntry(EntryVH vh, DisplayItem di) {
        TransferRecord record = di.entry;
        vh.item.bind(record);

        // 左侧缩进标识它是子项
        vh.item.setPadding(dp(28), vh.item.getPaddingTop(),
                vh.item.getPaddingRight(), vh.item.getPaddingBottom());
    }

    private int dp(int px) {
        return (int) (px * 3.5f + 0.5f);
    }

    /** 将毫秒时间戳转为对人友好文案，如"今天 14:30""昨天 09:15""7月25日 16:00"。 */
    @NonNull
    static String friendlyTime(long timeMillis) {
        java.util.Calendar now = java.util.Calendar.getInstance();
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.setTimeInMillis(timeMillis);

        SimpleDateFormat hm = new SimpleDateFormat("HH:mm", Locale.getDefault());
        String hourMin = hm.format(new Date(timeMillis));

        if (now.get(java.util.Calendar.YEAR) == cal.get(java.util.Calendar.YEAR)
                && now.get(java.util.Calendar.DAY_OF_YEAR) == cal.get(java.util.Calendar.DAY_OF_YEAR)) {
            return "今天 " + hourMin;
        }

        // 昨天
        java.util.Calendar yesterday = (java.util.Calendar) now.clone();
        yesterday.add(java.util.Calendar.DAY_OF_YEAR, -1);
        if (yesterday.get(java.util.Calendar.YEAR) == cal.get(java.util.Calendar.YEAR)
                && yesterday.get(java.util.Calendar.DAY_OF_YEAR) == cal.get(java.util.Calendar.DAY_OF_YEAR)) {
            return "昨天 " + hourMin;
        }

        // 更早 → M月d日 HH:mm
        SimpleDateFormat md = new SimpleDateFormat("M月d日 HH:mm", Locale.getDefault());
        return md.format(new Date(timeMillis));
    }

    // ── ViewHolders ─────────────────────────────────────

    static class GroupVH extends RecyclerView.ViewHolder {
        final TextView tvArrow, tvDevice, tvCount, tvTime;
        GroupVH(@NonNull View v) {
            super(v);
            tvArrow = v.findViewById(R.id.tvExpandArrow);
            tvDevice = v.findViewById(R.id.tvGroupDevice);
            tvCount = v.findViewById(R.id.tvGroupCount);
            tvTime = v.findViewById(R.id.tvGroupTime);
        }
    }

    static class EntryVH extends RecyclerView.ViewHolder {
        final TransferItemView item;
        EntryVH(TransferItemView v) { super(v); item = v; }
    }
}
