package github.linsmod.sharetopc.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
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

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_task, container, false);
        viewModel = new ViewModelProvider(requireActivity()).get(MainViewModel.class);

        TextView tvTaskTitle = view.findViewById(R.id.tvTaskTitle);
        Button btnSelectAll = view.findViewById(R.id.btnSelectAll);
        Button btnDeleteSelected = view.findViewById(R.id.btnDeleteSelected);
        Button btnCancelSelection = view.findViewById(R.id.btnCancelSelection);
        RecyclerView rvTask = view.findViewById(R.id.rvTask);

        rvTask.setLayoutManager(new LinearLayoutManager(requireContext()));
        taskAdapter = new TaskAdapter(new TaskAdapter.ActionListener() {
            @Override
            public void onRetryRequested(TransferRecord record) {
                viewModel.retry(record);
            }

            @Override
            public void onSelectionChanged(int count) {
                updateSelectionBar(count);
            }

            @Override
            public void onPauseRequested(TransferRecord record) {
                viewModel.pauseTransfer(record);
            }
        }, transferId -> viewModel.hasPfd(transferId));
        rvTask.setAdapter(taskAdapter);

        tvTaskTitle.setText(R.string.task_title);

        btnSelectAll.setOnClickListener(v -> taskAdapter.toggleSelectAll());
        btnDeleteSelected.setOnClickListener(v -> confirmDeleteSelected());
        btnCancelSelection.setOnClickListener(v -> taskAdapter.exitSelectionMode());

        viewModel.getTasks().observe(getViewLifecycleOwner(), taskAdapter::submitList);

        btnSelectAll.setOnClickListener(v -> taskAdapter.toggleSelectAll());
        btnDeleteSelected.setOnClickListener(v -> confirmDeleteSelected());
        btnCancelSelection.setOnClickListener(v -> taskAdapter.exitSelectionMode());

        viewModel.getTasks().observe(getViewLifecycleOwner(), taskAdapter::submitList);

        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        viewModel.refreshTasks();
    }

    private void updateSelectionBar(int count) {
        if (taskAdapter.isSelectionMode()) {
            TextView tvTaskTitle = getView().findViewById(R.id.tvTaskTitle);
            if (tvTaskTitle != null) {
                tvTaskTitle.setText(getString(R.string.task_title) + " ("
                        + getString(R.string.selected_count, count) + ")");
            }
            getView().findViewById(R.id.selectionBar).setVisibility(View.VISIBLE);
        } else {
            TextView tvTaskTitle = getView().findViewById(R.id.tvTaskTitle);
            if (tvTaskTitle != null) {
                tvTaskTitle.setText(R.string.task_title);
            }
            getView().findViewById(R.id.selectionBar).setVisibility(View.GONE);
        }
    }

    private void confirmDeleteSelected() {
        final java.util.List<String> ids = taskAdapter.getSelectedIds();
        if (ids.isEmpty()) {
            return;
        }
        new android.app.AlertDialog.Builder(requireActivity())
                .setMessage(getString(R.string.delete_selected_confirm, ids.size()))
                .setPositiveButton(R.string.delete, (dialog, which) -> {
                    viewModel.deleteRecords(ids);
                    taskAdapter.exitSelectionMode();
                    android.widget.Toast.makeText(requireContext(),
                            getString(R.string.records_deleted, ids.size()),
                            android.widget.Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }
}
