package github.linsmod.sharetopc.ui;

import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.Collections;

import github.linsmod.sharetopc.R;
import github.linsmod.sharetopc.data.TransferRecord;
import github.linsmod.sharetopc.discovery.DiscoveredDevice;
import github.linsmod.sharetopc.util.TransferStatusHelper;

/** 设备任务弹窗，复用 TaskAdapter 树状分组，展示传入的 TaskGroup。 */
public class DeviceQueueDialog extends Dialog {

    public interface Listener {
        void onPauseRequested(TransferRecord record);
        void onResumeRequested(TransferRecord record);
    }

    private final DiscoveredDevice device;
    private final Listener listener;
    @Nullable
    private final TaskGroup group;
    @Nullable
    private final TransferStatusHelper.PfdChecker pfdChecker;

    private TaskAdapter adapter;

    public DeviceQueueDialog(@NonNull Context context,
                             @NonNull DiscoveredDevice device,
                             @Nullable TaskGroup group,
                             Listener listener,
                             @Nullable TransferStatusHelper.PfdChecker pfdChecker) {
        super(context);
        this.device = device;
        this.group = group;
        this.listener = listener;
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

        android.widget.TextView tvTitle = findViewById(R.id.tvDialogTitle);
        tvTitle.setText(device.displayName() + " 的任务");

        findViewById(R.id.btnClose).setOnClickListener(v -> dismiss());
        findViewById(R.id.chipGroup).setVisibility(View.GONE);

        RecyclerView rvTaskList = findViewById(R.id.rvTaskList);
        rvTaskList.setLayoutManager(new LinearLayoutManager(getContext()));

        adapter = new TaskAdapter(new TaskAdapter.ActionListener() {
            @Override
            public void onRetryRequested(TransferRecord record) {
                if (listener != null) listener.onResumeRequested(record);
            }

            @Override
            public void onSelectionChanged(int count) {
                // 弹窗不需要多选
            }

            @Override
            public void onPauseRequested(TransferRecord record) {
                if (listener != null) listener.onPauseRequested(record);
            }
        }, pfdChecker);
        rvTaskList.setAdapter(adapter);

        View tvEmpty = findViewById(R.id.tvEmpty);

        if (group != null && !group.entries.isEmpty()) {
            group.recomputeProgress();
            group.expanded = true;
            adapter.submitList(Collections.singletonList(group));
            rvTaskList.setVisibility(View.VISIBLE);
            tvEmpty.setVisibility(View.GONE);
        } else {
            rvTaskList.setVisibility(View.GONE);
            tvEmpty.setVisibility(View.VISIBLE);
        }
    }
}
