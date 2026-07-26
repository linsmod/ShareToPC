package github.linsmod.sharetopc.ui;

import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LiveData;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.chip.ChipGroup;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import github.linsmod.sharetopc.R;
import github.linsmod.sharetopc.data.TransferRecord;
import github.linsmod.sharetopc.discovery.DiscoveredDevice;
import github.linsmod.sharetopc.util.TransferStatusHelper;

/** 设备任务弹窗，仅观察传输历史 LiveData，单一数据源。 */
public class DeviceQueueDialog extends Dialog {

    public interface Listener {
        void onTextPreviewRequested(TransferRecord record);
        /** 用户请求暂停当前传输。 */
        void onPauseTransfer();
        /** 用户请求取消当前传输。 */
        void onCancelTransfer();
        /** 用户请求继续/重试一条记录。 */
        void onResumeOrRetry(TransferRecord record);
    }

    private final DiscoveredDevice device;
    private final Listener listener;
    private final LifecycleOwner lifecycleOwner;
    private final LiveData<List<TransferRecord>> historyLiveData;
    @Nullable
    private final TransferStatusHelper.PfdChecker pfdChecker;

    private DeviceTaskAdapter adapter;
    private RecyclerView rvTaskList;
    private TextView tvEmpty;

    private static final int FILTER_ALL = 0;
    private static final int FILTER_TRANSFERRING = 1;
    private static final int FILTER_COMPLETED = 2;
    private static final int FILTER_FAILED = 3;
    private int currentFilter = FILTER_ALL;

    public DeviceQueueDialog(@NonNull Context context,
                             @NonNull DiscoveredDevice device,
                             Listener listener,
                             @NonNull LifecycleOwner lifecycleOwner,
                             @NonNull LiveData<List<TransferRecord>> historyLiveData,
                             @Nullable TransferStatusHelper.PfdChecker pfdChecker) {
        super(context);
        this.device = device;
        this.listener = listener;
        this.lifecycleOwner = lifecycleOwner;
        this.historyLiveData = historyLiveData;
        this.pfdChecker = pfdChecker;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.dialog_device_queue);

        Window window = getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                    (int) (getContext().getResources().getDisplayMetrics().heightPixels * 0.75));
            window.setWindowAnimations(0);
        }

        TextView tvTitle = findViewById(R.id.tvDialogTitle);
        tvTitle.setText(device.displayName() + " 的任务");

        findViewById(R.id.btnClose).setOnClickListener(v -> dismiss());

        ChipGroup chipGroup = findViewById(R.id.chipGroup);
        chipGroup.check(R.id.chipAll);
        chipGroup.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (checkedIds.isEmpty()) return;
            int id = checkedIds.get(0);
            if (id == R.id.chipAll) currentFilter = FILTER_ALL;
            else if (id == R.id.chipTransferring) currentFilter = FILTER_TRANSFERRING;
            else if (id == R.id.chipCompleted) currentFilter = FILTER_COMPLETED;
            else if (id == R.id.chipFailed) currentFilter = FILTER_FAILED;
            computeAndSubmit();
        });

        rvTaskList = findViewById(R.id.rvTaskList);
        rvTaskList.setLayoutManager(new LinearLayoutManager(getContext()));
        adapter = new DeviceTaskAdapter();
        adapter.setTaskActionListener(new DeviceTaskAdapter.TaskActionListener() {
            @Override
            public void onPauseTransfer(String taskId) {
                if (listener != null) listener.onPauseTransfer();
            }

            @Override
            public void onCancelTransfer(String taskId) {
                if (listener != null) listener.onCancelTransfer();
            }

            @Override
            public void onResumeOrRetry(DeviceTaskAdapter.TaskItem item) {
                if (listener != null) {
                    // 从历史数据中找回原件
                    List<TransferRecord> records = historyLiveData.getValue();
                    if (records != null) {
                        for (TransferRecord r : records) {
                            if (r.id.equals(item.id)) {
                                listener.onResumeOrRetry(r);
                                return;
                            }
                        }
                    }
                }
            }
        });
        rvTaskList.setAdapter(adapter);

        tvEmpty = findViewById(R.id.tvEmpty);

        historyLiveData.observe(lifecycleOwner, ignored -> computeAndSubmit());
    }

    private void computeAndSubmit() {
        String deviceKey = device.key();
        List<TransferRecord> list = historyLiveData.getValue();
        List<DeviceTaskAdapter.TaskItem> allItems = new ArrayList<>();

        for (TransferRecord tr : list != null ? list : Collections.<TransferRecord>emptyList()) {
            if (tr.host == null) continue;
            if (!deviceKey.equals(tr.host + ":" + tr.port)) continue;
            String s = tr.statusOrLegacy();
            if (TransferRecord.STATUS_PENDING.equals(s)) continue;

            int status;
            boolean isCurrent;
            if (TransferRecord.STATUS_SENDING.equals(s)) {
                status = DeviceTaskAdapter.STATUS_TRANSFERRING;
                isCurrent = true;
            } else if (TransferRecord.STATUS_CANCELLED.equals(s)) {
                status = DeviceTaskAdapter.STATUS_CANCELLED;
                isCurrent = false;
            } else if (TransferRecord.STATUS_SUCCESS.equals(s)) {
                status = DeviceTaskAdapter.STATUS_SUCCESS;
                isCurrent = false;
            } else {
                status = DeviceTaskAdapter.STATUS_FAILED;
                isCurrent = false;
            }

            boolean canResume = (status == DeviceTaskAdapter.STATUS_CANCELLED
                    || status == DeviceTaskAdapter.STATUS_FAILED)
                    && TransferStatusHelper.canResumeOrRetry(tr, pfdChecker);
            allItems.add(new DeviceTaskAdapter.TaskItem(
                    tr.id, tr.name, tr.size, tr.timeMillis, status, -1, isCurrent, canResume));
        }

        List<DeviceTaskAdapter.TaskItem> filtered;
        switch (currentFilter) {
            case FILTER_ALL:
                filtered = allItems;
                break;
            case FILTER_TRANSFERRING:
                filtered = new ArrayList<>();
                for (DeviceTaskAdapter.TaskItem item : allItems) {
                    if (item.status == DeviceTaskAdapter.STATUS_TRANSFERRING) filtered.add(item);
                }
                break;
            case FILTER_COMPLETED:
                filtered = new ArrayList<>();
                for (DeviceTaskAdapter.TaskItem item : allItems) {
                    if (item.status == DeviceTaskAdapter.STATUS_SUCCESS) filtered.add(item);
                }
                break;
            case FILTER_FAILED:
                filtered = new ArrayList<>();
                for (DeviceTaskAdapter.TaskItem item : allItems) {
                    if (item.status == DeviceTaskAdapter.STATUS_FAILED
                            || item.status == DeviceTaskAdapter.STATUS_CANCELLED) filtered.add(item);
                }
                break;
            default:
                filtered = allItems;
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

    /** 外部更新进度时调用。 */
    public void updateProgress(int percent) {
        adapter.updateTransferProgress(percent);
    }
}
