package github.linsmod.sharetopc.ui;

import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

import github.linsmod.sharetopc.R;

public class DeviceActionAdapter extends RecyclerView.Adapter<DeviceActionAdapter.ViewHolder> {

    public static class ActionItem {
        public final String label;
        public final int iconRes;
        public final int iconTint;
        public final String category;

        public ActionItem(String label, int iconRes, int iconTint, @Nullable String category) {
            this.label = label;
            this.iconRes = iconRes;
            this.iconTint = iconTint;
            this.category = category;
        }
    }

    public interface OnActionClickListener {
        void onClick(ActionItem item);
    }

    private final List<ActionItem> items;
    private final OnActionClickListener listener;

    public DeviceActionAdapter(List<ActionItem> items, OnActionClickListener listener) {
        this.items = items;
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_device_action, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        ActionItem item = items.get(position);
        holder.btn.setText(item.label);
        Drawable icon = ContextCompat.getDrawable(holder.btn.getContext(), item.iconRes);
        holder.btn.setIcon(icon);
        holder.btn.setIconTintResource(item.iconTint);
        holder.btn.setOnClickListener(v -> listener.onClick(item));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final TextButton btn;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            btn = itemView.findViewById(R.id.btnCategory);
        }
    }
}
