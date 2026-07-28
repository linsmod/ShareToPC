package github.linsmod.sharetopc.ui;

import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;

import github.linsmod.sharetopc.App;
import github.linsmod.sharetopc.MainViewModel;
import github.linsmod.sharetopc.R;
import github.linsmod.sharetopc.data.TransferRecord;

public class TaskFragment extends Fragment {

    private MainViewModel viewModel;
    private TaskAdapter taskAdapter;

    /** 任务变更回调（注册时立即收到当前值，避免 LiveData lifecycle 延迟）。 */
    private final MainViewModel.TasksChangeListener tasksListener = tasks -> {
        if (taskAdapter != null && getView() != null) {
            taskAdapter.submitList(tasks);
        }
    };

    /** 条目进度增量更新回调。 */
    private final MainViewModel.EntryUpdateListener entryUpdateListener = entry -> {
        if (taskAdapter != null) {
            taskAdapter.onEntryProgressChanged(entry);
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_task, container, false);
        viewModel = new ViewModelProvider(requireActivity()).get(MainViewModel.class);

        TextView tvTaskTitle = view.findViewById(R.id.tvTaskTitle);
        RecyclerView rvTask = view.findViewById(R.id.rvTask);

        rvTask.setLayoutManager(new LinearLayoutManager(requireContext()));
        rvTask.setItemAnimator(null); // 禁用动画，避免频繁 notifyItemChanged 导致闪烁
        taskAdapter = new TaskAdapter(new Listeners.TaskActionListener() {

            @Override
            public void onGroupMenuRequested(TaskGroup group, View anchor, float touchX) {
                showGroupMenu(group, anchor, touchX);
            }
        }, transferId -> viewModel.hasPfd(transferId));
        rvTask.setAdapter(taskAdapter);

        tvTaskTitle.setText(R.string.task_title);

        FloatingActionButton fabClearTasks = view.findViewById(R.id.fabClearTasks);
        fabClearTasks.setOnClickListener(v -> showClearTasksDialog());

        viewModel.addTasksChangeListener(tasksListener);
        viewModel.addEntryUpdateListener(entryUpdateListener);

        return view;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        viewModel.removeTasksChangeListener(tasksListener);
        viewModel.removeEntryUpdateListener(entryUpdateListener);
    }

    @Override
    public void onResume() {
        super.onResume();
        viewModel.refreshTasks();
    }

    private void showClearTasksDialog() {
        new AlertDialog.Builder(requireContext())
                .setTitle("清空任务记录")
                .setMessage("确定要清空所有传输任务记录吗？")
                .setPositiveButton(R.string.ok, (d, which) -> {
                    App.get().jobStore.clear();
                    viewModel.refreshHistory();
                    Toast.makeText(requireContext(), R.string.task_cleared, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void showGroupMenu(TaskGroup group, View anchor, float touchX) {
        PopupMenu popup = new PopupMenu(requireActivity(), anchor);
        popup.getMenuInflater().inflate(R.menu.group_context_menu, popup.getMenu());

        // 根据触摸横向位置安排菜单对齐方向：按在右半边则菜单右对齐
        int gravity = touchX > anchor.getWidth() * 0.5f ? Gravity.END : Gravity.START;
        popup.setGravity(gravity);

        // 根据分组状态启用/禁用菜单项
        boolean hasActive = group.hasActive();
        boolean hasResumable = false;
        for (TransferRecord r : group.entries) {
            if (r.status == TransferRecord.STATUS_IDLE
                    && r.idleReason != TransferRecord.IDLE_REASON_SUCCESS
                    && r.pendingAction == 0
                    && github.linsmod.sharetopc.util.TransferStatusHelper.canResumeOrRetry(r,
                            transferId -> viewModel.hasPfd(transferId))) {
                hasResumable = true;
                break;
            }
        }

        popup.getMenu().findItem(R.id.action_pause_group).setEnabled(hasActive);
        popup.getMenu().findItem(R.id.action_resume_group).setEnabled(hasResumable && !hasActive);

        popup.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == R.id.action_pause_group) {
                viewModel.pauseGroup(group);
                Toast.makeText(requireContext(), "已暂停传输", Toast.LENGTH_SHORT).show();
                return true;
            } else if (id == R.id.action_resume_group) {
                viewModel.resumeGroup(group);
                return true;
            } else if (id == R.id.action_delete_group) {
                viewModel.deleteGroup(group);
                Toast.makeText(requireContext(),
                        getString(R.string.group_deleted, group.totalEntries),
                        Toast.LENGTH_SHORT).show();
                return true;
            }
            return false;
        });

        popup.show();
    }
}
