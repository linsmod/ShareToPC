package github.linsmod.sharetopc.ui;

import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

import github.linsmod.sharetopc.R;

/**
 * 设备操作页适配器：支持三种 viewType。
 * header → section 标题（spanSize=3 占满行）
 * action → 网格按钮（spanSize=1 占 1/3 行）
 * listItem → 列表行（spanSize=3 占满行，图标+文字，用于磁盘文件）
 */
public class DeviceTasksAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_ACTION = 1;
    private static final int TYPE_LIST_ITEM = 2;

    public static class ActionItem {
        public final String label;
        public final int iconRes;
        public final int iconTint;
        @Nullable public final String category;
        @Nullable public final String browsePath;

        public ActionItem(String label, int iconRes, int iconTint,
                          @Nullable String category, @Nullable String browsePath) {
            this.label = label;
            this.iconRes = iconRes;
            this.iconTint = iconTint;
            this.category = category;
            this.browsePath = browsePath;
        }

        public ActionItem(String label, int iconRes, int iconTint, @Nullable String category) {
            this(label, iconRes, iconTint, category, null);
        }
    }

    public static class ListItem {
        public final String label;
        public final int iconRes;
        @Nullable public final String browsePath;

        public ListItem(String label, int iconRes, @Nullable String browsePath) {
            this.label = label;
            this.iconRes = iconRes;
            this.browsePath = browsePath;
        }
    }

    public static class HeaderItem {
        public final String title;
        @Nullable public final String actionText;

        public HeaderItem(String title) { this(title, null); }

        public HeaderItem(String title, @Nullable String actionText) {
            this.title = title;
            this.actionText = actionText;
        }
    }

    public static class SpanSizeProvider extends androidx.recyclerview.widget.GridLayoutManager.SpanSizeLookup {
        private final List<Object> items;

        public SpanSizeProvider(List<Object> items) { this.items = items; }

        @Override
        public int getSpanSize(int position) {
            Object item = items.get(position);
            return (item instanceof HeaderItem || item instanceof ListItem) ? 3 : 1;
        }
    }

    public interface OnItemClickListener {
        void onClick(ActionItem item);
        void onListItemClick(ListItem item);
        void onHeaderActionClick(String actionText);
    }

    private final List<Object> items = new ArrayList<>();
    private final OnItemClickListener listener;

    public DeviceTasksAdapter(OnItemClickListener listener) {
        this.listener = listener;
    }

    public List<Object> getItems() { return items; }

    public void setItems(List<Object> newItems) {
        items.clear();
        items.addAll(newItems);
        notifyDataSetChanged();
    }

    @Override
    public int getItemViewType(int position) {
        Object item = items.get(position);
        if (item instanceof HeaderItem) return TYPE_HEADER;
        if (item instanceof ListItem) return TYPE_LIST_ITEM;
        return TYPE_ACTION;
    }

    @Override
    public int getItemCount() { return items.size(); }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == TYPE_HEADER) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_device_task_header, parent, false);
            return new HeaderViewHolder(v);
        }
        if (viewType == TYPE_LIST_ITEM) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_device_task_list, parent, false);
            return new ListItemViewHolder(v);
        }
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_device_action, parent, false);
        return new ActionViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (holder instanceof HeaderViewHolder) {
            HeaderItem h = (HeaderItem) items.get(position);
            HeaderViewHolder hh = (HeaderViewHolder) holder;
            hh.tvTitle.setText(h.title);
            if (h.actionText != null && !h.actionText.isEmpty()) {
                hh.btnAction.setText(h.actionText);
                hh.btnAction.setVisibility(View.VISIBLE);
                hh.btnAction.setOnClickListener(v -> listener.onHeaderActionClick(h.actionText));
            } else {
                hh.btnAction.setVisibility(View.GONE);
            }
        } else if (holder instanceof ListItemViewHolder) {
            ListItem li = (ListItem) items.get(position);
            ListItemViewHolder vh = (ListItemViewHolder) holder;
            vh.tvLabel.setText(li.label);
            vh.ivIcon.setImageResource(li.iconRes);
            vh.itemView.setOnClickListener(v -> listener.onListItemClick(li));
        } else {
            ActionItem a = (ActionItem) items.get(position);
            ActionViewHolder vh = (ActionViewHolder) holder;
            vh.btn.setText(a.label);
            Drawable icon = ContextCompat.getDrawable(vh.btn.getContext(), a.iconRes);
            vh.btn.setIcon(icon);
            vh.btn.setIconTintResource(a.iconTint);
            vh.btn.setOnClickListener(v -> listener.onClick(a));
        }
    }

    static class HeaderViewHolder extends RecyclerView.ViewHolder {
        final TextView tvTitle;
        final TextButton btnAction;
        HeaderViewHolder(@NonNull View itemView) {
            super(itemView);
            tvTitle = itemView.findViewById(R.id.tvSectionHeader);
            btnAction = itemView.findViewById(R.id.btnHeaderAction);
        }
    }

    static class ListItemViewHolder extends RecyclerView.ViewHolder {
        final ImageView ivIcon;
        final TextView tvLabel;
        ListItemViewHolder(@NonNull View itemView) {
            super(itemView);
            ivIcon = itemView.findViewById(R.id.ivListIcon);
            tvLabel = itemView.findViewById(R.id.tvListLabel);
        }
    }

    static class ActionViewHolder extends RecyclerView.ViewHolder {
        final TextButton btn;
        ActionViewHolder(@NonNull View itemView) {
            super(itemView);
            btn = itemView.findViewById(R.id.btnCategory);
        }
    }
}
