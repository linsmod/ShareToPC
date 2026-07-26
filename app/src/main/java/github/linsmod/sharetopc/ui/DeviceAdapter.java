package github.linsmod.sharetopc.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

import github.linsmod.sharetopc.R;
import github.linsmod.sharetopc.discovery.DiscoveredDevice;

/** 设备列表适配器。 */
public class DeviceAdapter extends RecyclerView.Adapter<DeviceAdapter.VH> {

    public interface OnDeviceClickListener {
        void onClick(DiscoveredDevice device);

        /** 长按：编辑设备备注名。 */
        void onLongClick(DiscoveredDevice device);
    }

    private final List<DiscoveredDevice> items = new ArrayList<>();
    private final OnDeviceClickListener clickListener;

    public DeviceAdapter(OnDeviceClickListener clickListener) {
        this.clickListener = clickListener;
    }

    public void submitList(List<DiscoveredDevice> list) {
        items.clear();
        if (list != null) {
            items.addAll(list);
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_device, parent, false);
        return new VH(view);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        DiscoveredDevice device = items.get(position);
        holder.tvName.setText(device.displayName());
        holder.tvAddr.setText(device.host + ":" + device.port);
        holder.itemView.setOnClickListener(v -> {
            if (clickListener != null) {
                clickListener.onClick(device);
            }
        });
        holder.itemView.setOnLongClickListener(v -> {
            if (clickListener != null) {
                clickListener.onLongClick(device);
                return true;
            }
            return false;
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final TextView tvName;
        final TextView tvAddr;

        VH(@NonNull View itemView) {
            super(itemView);
            tvName = itemView.findViewById(R.id.tvDeviceName);
            tvAddr = itemView.findViewById(R.id.tvDeviceAddr);
        }
    }
}
