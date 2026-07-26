package github.linsmod.sharetopc.ui;

import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;

import java.util.ArrayList;
import java.util.List;

import github.linsmod.sharetopc.App;
import github.linsmod.sharetopc.R;
import github.linsmod.sharetopc.data.TransferRecord;
import github.linsmod.sharetopc.discovery.DiscoveredDevice;
import github.linsmod.sharetopc.transfer.TransferManager;

/** 设备任务队列弹窗，支持 全部 / 传输中 / 已完成 筛选。 */
public class DeviceQueueDialog extends Dialog {

    public interface Listener {
        /** 任务是文本类型时，点击预览全文。 */
        void onTextPreviewRequested(TransferRecord record);
    }

    private final DiscoveredDevice device;
    private final Listener listener;

    private DeviceTaskAdapter adapter;
    private RecyclerView rvTaskList;
    private TextView tvEmpty;
    private ChipGroup chipGroup;

    private static final int FILTER_ALL = 0;
    private static final int FILTER_TRANSFERRING = 1;
    private static final int FILTER_COMPLETED = 2;
    private int currentFilter = FILTER_ALL;

    public DeviceQueueDialog(@NonNull Context context,
                             @NonNull DiscoveredDevice device,
                             Listener listener) {
        super(context);
        this.device = device;
        this.listener = listener;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.dialog_device_queue);

        // 全屏宽度 + 75% 高度
        Window window = getWindow();
        if (window != null) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                    (int) (getContext().getResources().getDisplayMetrics().heightPixels * 0.75));
            window.setWindowAnimations(0); // 无动画，简洁
        }

        TextView tvTitle = findViewById(R.id.tvDialogTitle);
        tvTitle.setText(device.displayName() + " 的任务");

        findViewById(R.id.btnClose).setOnClickListener(v -> dismiss());

        chipGroup = findViewById(R.id.chipGroup);
        chipGroup.check(R.id.chipAll);
        chipGroup.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (checkedIds.isEmpty()) {
                return;
            }
            int id = checkedIds.get(0);
            if (id == R.id.chipAll) {
                currentFilter = FILTER_ALL;
            } else if (id == R.id.chipTransferring) {
                currentFilter = FILTER_TRANSFERRING;
            } else if (id == R.id.chipCompleted) {
                currentFilter = FILTER_COMPLETED;
            }
            refresh();
        });

        rvTaskList = findViewById(R.id.rvTaskList);
        rvTaskList.setLayoutManager(new LinearLayoutManager(getContext()));
        adapter = new DeviceTaskAdapter();
        rvTaskList.setAdapter(adapter);

        tvEmpty = findViewById(R.id.tvEmpty);

        refresh();
    }

    /** 重新加载数据（外部数据变化时调用）。 */
    public void refresh() {
        String deviceKey = device.key();
        List<DeviceTaskAdapter.TaskItem> allItems = new ArrayList<>();

        TransferManager tm = App.get().transferManager;

        // 先收集历史记录中本设备的已完成 transferId，避免在"传输中"区域重复显示
        java.util.Set<String> completedIds = new java.util.HashSet<>();
        List<TransferRecord> history = App.get().historyStore.all();
        for (TransferRecord tr : history) {
            if (tr.host == null) continue;
            String key = tr.host + ":" + tr.port;
            if (!key.equals(deviceKey)) continue;
            if (tr.isPending()) continue;
            if (tr.transferId != null) {
                completedIds.add(tr.transferId);
            }
        }

        // —— 传输中的任务（按 transferId 排除已在历史中完成的文件）——
        TransferManager.SendJob currentJob = tm.getCurrentJob();
        if (currentJob != null && deviceKey.equals(currentJob.host + ":" + currentJob.port)) {
            for (int i = 0; i < currentJob.items.size(); i++) {
                github.linsmod.sharetopc.data.PendingContent item = currentJob.items.get(i);
                if (item.transferId != null && completedIds.contains(item.transferId)) continue;
                allItems.add(new DeviceTaskAdapter.TaskItem(
                        currentJob.id + "_" + i, item.name,
                        item.size, System.currentTimeMillis(),
                        DeviceTaskAdapter.STATUS_TRANSFERRING, -1, true));
            }
        }

        // —— 排队中的任务 ——
        List<TransferManager.SendJob> queued = tm.getQueuedJobsForDevice(deviceKey);
        for (TransferManager.SendJob job : queued) {
            if (job.items.size() == 1) {
                allItems.add(new DeviceTaskAdapter.TaskItem(
                        job.id, job.items.get(0).name,
                        job.items.get(0).size, System.currentTimeMillis(),
                        DeviceTaskAdapter.STATUS_WAITING, -1, false));
            } else {
                for (int i = 0; i < job.items.size(); i++) {
                    github.linsmod.sharetopc.data.PendingContent item = job.items.get(i);
                    allItems.add(new DeviceTaskAdapter.TaskItem(
                            job.id + "_" + i, item.name,
                            item.size, System.currentTimeMillis(),
                            DeviceTaskAdapter.STATUS_WAITING, -1, false));
                }
            }
        }

        // —— 历史记录 ——
        for (TransferRecord tr : history) {
            // 只匹配 host:port（PENDING 记录没有 host 则排除）
            if (tr.host == null) {
                continue;
            }
            String key = tr.host + ":" + tr.port;
            if (!key.equals(deviceKey)) {
                continue;
            }
            int status;
            if (tr.isPending()) {
                continue; // PENDING 暂存不在已完成筛选里出现
            } else if (tr.success) {
                status = DeviceTaskAdapter.STATUS_SUCCESS;
            } else {
                status = DeviceTaskAdapter.STATUS_FAILED;
            }
            allItems.add(new DeviceTaskAdapter.TaskItem(
                    tr.id, tr.name, tr.size, tr.timeMillis,
                    status, 100, false));
        }

        // —— 筛选 ——
        List<DeviceTaskAdapter.TaskItem> filtered = new ArrayList<>();
        switch (currentFilter) {
            case FILTER_ALL:
                filtered = allItems;
                break;
            case FILTER_TRANSFERRING:
                for (DeviceTaskAdapter.TaskItem item : allItems) {
                    if (item.status == DeviceTaskAdapter.STATUS_TRANSFERRING
                            || item.status == DeviceTaskAdapter.STATUS_WAITING) {
                        filtered.add(item);
                    }
                }
                break;
            case FILTER_COMPLETED:
                for (DeviceTaskAdapter.TaskItem item : allItems) {
                    if (item.status == DeviceTaskAdapter.STATUS_SUCCESS
                            || item.status == DeviceTaskAdapter.STATUS_FAILED) {
                        filtered.add(item);
                    }
                }
                break;
        }

        if (filtered.isEmpty()) {
            rvTaskList.setVisibility(View.GONE);
            tvEmpty.setVisibility(View.VISIBLE);
        } else {
            rvTaskList.setVisibility(View.VISIBLE);
            tvEmpty.setVisibility(View.GONE);
        }
        adapter.submitList(filtered);
    }

    /** 外部更新进度时调用（仅更新传输中条目的百分比，不重建列表）。 */
    public void updateProgress(int percent) {
        adapter.updateTransferProgress(percent);
    }
}
