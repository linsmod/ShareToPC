package github.linsmod.sharetopc.ui;

import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.PopupMenu;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.Collections;

import github.linsmod.sharetopc.MainViewModel;
import github.linsmod.sharetopc.R;
import github.linsmod.sharetopc.data.TransferRecord;
import github.linsmod.sharetopc.discovery.DiscoveredDevice;
import github.linsmod.sharetopc.util.TransferStatusHelper;

/** 设备任务弹窗，复用 TaskAdapter 树状分组，展示传入的 TaskGroup。 */
public class DeviceQueueDialog extends Dialog {

    private final DiscoveredDevice device;
    private final MainViewModel viewModel;
    private final Listeners.TaskActionListener listener;
    @Nullable
    private final TaskGroup group;
    @Nullable
    private final TransferStatusHelper.PfdChecker pfdChecker;

    private TaskAdapter adapter;
    private MainViewModel.EntryUpdateListener entryUpdateListener;

    public DeviceQueueDialog(@NonNull Context context,
                             @NonNull DiscoveredDevice device,
                             @Nullable TaskGroup group,
                             Listeners.TaskActionListener listener,
                             @Nullable TransferStatusHelper.PfdChecker pfdChecker,
                             @NonNull MainViewModel viewModel) {
        super(context);
        this.device = device;
        this.viewModel = viewModel;
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
        rvTaskList.setItemAnimator(null); // 禁用动画，避免频繁更新闪烁

        adapter = new TaskAdapter(new Listeners.TaskActionListener() {
            @Override
            public void onRetryRequested(TransferRecord record) {
                if (listener != null) listener.onRetryRequested(record);
            }

            @Override
            public void onPauseRequested(TransferRecord record) {
                if (listener != null) listener.onPauseRequested(record);
            }

            @Override
            public void onGroupMenuRequested(TaskGroup group, View anchor, float touchX) {
                showGroupMenu(group, anchor, touchX);
            }
        }, pfdChecker);
        rvTaskList.setAdapter(adapter);

        entryUpdateListener = entry -> {
            if (adapter != null) {
                adapter.onEntryProgressChanged(entry);
            }
        };
        viewModel.addEntryUpdateListener(entryUpdateListener);

        View tvEmpty = findViewById(R.id.tvEmpty);

        if (group != null && !group.entries.isEmpty()) {
            group.recomputeProgress();
            group.expanded = true;
            group.showDeviceInfo = false; // 弹窗标题已显示设备名，分组内不再重复
            adapter.submitList(Collections.singletonList(group));
            rvTaskList.setVisibility(View.VISIBLE);
            tvEmpty.setVisibility(View.GONE);
        } else {
            rvTaskList.setVisibility(View.GONE);
            tvEmpty.setVisibility(View.VISIBLE);
        }
    }

    @Override
    public void dismiss() {
        if (entryUpdateListener != null) {
            viewModel.removeEntryUpdateListener(entryUpdateListener);
            entryUpdateListener = null;
        }
        super.dismiss();
    }

    private void showGroupMenu(TaskGroup group, View anchor, float touchX) {
        PopupMenu popup = new PopupMenu(getContext(), anchor);
        popup.getMenuInflater().inflate(R.menu.group_context_menu, popup.getMenu());

        int gravity = touchX > anchor.getWidth() * 0.5f ? Gravity.END : Gravity.START;
        popup.setGravity(gravity);

        boolean hasActive = group.hasActive();
        boolean hasResumable = false;
        for (TransferRecord r : group.entries) {
            if (r.status == TransferRecord.STATUS_IDLE
                    && r.idleReason != TransferRecord.IDLE_REASON_SUCCESS
                    && r.pendingAction == 0
                    && github.linsmod.sharetopc.util.TransferStatusHelper.canResumeOrRetry(r, pfdChecker)) {
                hasResumable = true;
                break;
            }
        }

        popup.getMenu().findItem(R.id.action_pause_group).setEnabled(hasActive);
        popup.getMenu().findItem(R.id.action_resume_group).setEnabled(hasResumable && !hasActive);

        popup.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == R.id.action_pause_group) {
                if (listener != null) listener.onGroupPauseRequested(group);
                return true;
            } else if (id == R.id.action_resume_group) {
                if (listener != null) listener.onGroupResumeRequested(group);
                return true;
            } else if (id == R.id.action_delete_group) {
                if (listener != null) listener.onGroupDeleteRequested(group);
                return true;
            }
            return false;
        });

        popup.show();
    }
}
