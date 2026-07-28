package github.linsmod.sharetopc.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

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

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_task, container, false);
        viewModel = new ViewModelProvider(requireActivity()).get(MainViewModel.class);

        TextView tvTaskTitle = view.findViewById(R.id.tvTaskTitle);
        RecyclerView rvTask = view.findViewById(R.id.rvTask);

        rvTask.setLayoutManager(new LinearLayoutManager(requireContext()));
        taskAdapter = new TaskAdapter(new TaskAdapter.ActionListener() {
            @Override
            public void onRetryRequested(TransferRecord record) {
                viewModel.retry(record);
            }

            @Override
            public void onPauseRequested(TransferRecord record) {
                viewModel.pauseTransfer(record);
            }
        }, transferId -> viewModel.hasPfd(transferId));
        rvTask.setAdapter(taskAdapter);

        tvTaskTitle.setText(R.string.task_title);

        viewModel.addTasksChangeListener(tasksListener);

        return view;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        viewModel.removeTasksChangeListener(tasksListener);
    }

    @Override
    public void onResume() {
        super.onResume();
        viewModel.refreshTasks();
    }
}
