package github.linsmod.sharetopc.ui;

import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import github.linsmod.sharetopc.R;
import github.linsmod.sharetopc.data.SendEntry;
import github.linsmod.sharetopc.data.TransferRecord;
import github.linsmod.sharetopc.util.FileUtils;
import github.linsmod.sharetopc.util.TransferStatusHelper;

/** 设备任务队列适配器，直接使用 TransferRecord 作为数据源。 */
public class DeviceTaskAdapter extends RecyclerView.Adapter<DeviceTaskAdapter.VH> {

    public interface TaskActionListener {
        void onToggle(TransferRecord record);
    }

    private final List<TransferRecord> items = new ArrayList<>();

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

    public void submitList(List<TransferRecord> list) {
        // TODO
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        TransferItemView view = new TransferItemView(parent.getContext());
        return new VH(view);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        TransferRecord record = items.get(position);
        TransferItemView view = holder.item;
        view.bind(record);
    }

    @Override
    public int getItemCount() { return items.size(); }

    static class VH extends RecyclerView.ViewHolder {
        final TransferItemView item;
        VH(TransferItemView v) { super(v); item = v; }
    }
}
