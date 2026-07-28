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

public class HistoryFragment extends Fragment {

    private MainViewModel viewModel;
    private HistoryAdapter historyAdapter;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_history, container, false);
        viewModel = new ViewModelProvider(requireActivity()).get(MainViewModel.class);

        TextView tvHistoryTitle = view.findViewById(R.id.tvHistoryTitle);
        Button btnSelectAll = view.findViewById(R.id.btnSelectAll);
        Button btnDeleteSelected = view.findViewById(R.id.btnDeleteSelected);
        Button btnCancelSelection = view.findViewById(R.id.btnCancelSelection);
        RecyclerView rvHistory = view.findViewById(R.id.rvHistory);

        rvHistory.setLayoutManager(new LinearLayoutManager(requireContext()));
        historyAdapter = new HistoryAdapter(new HistoryAdapter.ActionListener() {
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
        rvHistory.setAdapter(historyAdapter);

        tvHistoryTitle.setText(R.string.history_title);

        btnSelectAll.setOnClickListener(v -> historyAdapter.toggleSelectAll());
        btnDeleteSelected.setOnClickListener(v -> confirmDeleteSelected());
        btnCancelSelection.setOnClickListener(v -> historyAdapter.exitSelectionMode());

        viewModel.getHistory().observe(getViewLifecycleOwner(), historyAdapter::submitList);

        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        viewModel.refreshHistory();
    }

    private void updateSelectionBar(int count) {
        if (historyAdapter.isSelectionMode()) {
            TextView tvHistoryTitle = getView().findViewById(R.id.tvHistoryTitle);
            if (tvHistoryTitle != null) {
                tvHistoryTitle.setText(getString(R.string.history_title) + " ("
                        + getString(R.string.selected_count, count) + ")");
            }
            getView().findViewById(R.id.selectionBar).setVisibility(View.VISIBLE);
        } else {
            TextView tvHistoryTitle = getView().findViewById(R.id.tvHistoryTitle);
            if (tvHistoryTitle != null) {
                tvHistoryTitle.setText(R.string.history_title);
            }
            getView().findViewById(R.id.selectionBar).setVisibility(View.GONE);
        }
    }

    private void confirmDeleteSelected() {
        final java.util.List<String> ids = historyAdapter.getSelectedIds();
        if (ids.isEmpty()) {
            return;
        }
        new android.app.AlertDialog.Builder(requireActivity())
                .setMessage(getString(R.string.delete_selected_confirm, ids.size()))
                .setPositiveButton(R.string.delete, (dialog, which) -> {
                    viewModel.deleteRecords(ids);
                    historyAdapter.exitSelectionMode();
                    android.widget.Toast.makeText(requireContext(),
                            getString(R.string.records_deleted, ids.size()),
                            android.widget.Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }
}
