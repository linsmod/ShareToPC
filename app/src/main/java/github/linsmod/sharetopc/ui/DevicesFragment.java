package github.linsmod.sharetopc.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.PopupMenu;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import github.linsmod.sharetopc.MainActivity;
import github.linsmod.sharetopc.MainViewModel;
import github.linsmod.sharetopc.R;
import github.linsmod.sharetopc.discovery.DiscoveredDevice;

public class DevicesFragment extends Fragment {

    private MainViewModel viewModel;
    private DeviceAdapter deviceAdapter;
    private TextView tvConnState;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_devices, container, false);
        viewModel = new ViewModelProvider(requireActivity()).get(MainViewModel.class);

        tvConnState = view.findViewById(R.id.tvConnState);
        TextView tvDevicesTitle = view.findViewById(R.id.tvDevicesTitle);
        Button btnRefreshDevices = view.findViewById(R.id.btnRefreshDevices);
        RecyclerView rvDevices = view.findViewById(R.id.rvDevices);

        tvDevicesTitle.setText(R.string.devices_title);
        rvDevices.setLayoutManager(new LinearLayoutManager(requireContext()));
        rvDevices.setItemAnimator(null); // 禁用动画，避免进度更新时 item 交叉淡入淡出闪烁
        deviceAdapter = new DeviceAdapter(new DeviceAdapter.OnDeviceClickListener() {
            @Override
            public void onClick(DiscoveredDevice device) {
                ((MainActivity) requireActivity()).showTaskForDevice(device);
            }

            @Override
            public void onLongClick(View anchor, DiscoveredDevice device) {
                showDeviceContextMenu(anchor, device);
            }
        });
        rvDevices.setAdapter(deviceAdapter);
        deviceAdapter.setOnAddDeviceClickListener(() ->
                ((MainActivity) requireActivity()).showManualDeviceDialog());
        deviceAdapter.setOnPcClientClickListener(() ->
                ((MainActivity) requireActivity()).showPcClientDialog());
        deviceAdapter.setOnShareClipboardClickListener(() ->
                ((MainActivity) requireActivity()).showShareClipboardDialog());

        btnRefreshDevices.setOnClickListener(v -> viewModel.startDiscovery());

        viewModel.getDevices().observe(getViewLifecycleOwner(), deviceAdapter::submitList);
        viewModel.getConnState().observe(getViewLifecycleOwner(), tvConnState::setText);
        viewModel.setDeviceProgressListener(deviceAdapter::notifyProgressChanged);

        return view;
    }

    private void showDeviceContextMenu(View anchor, DiscoveredDevice device) {
        PopupMenu popup = new PopupMenu(requireContext(), anchor);
        popup.getMenuInflater().inflate(R.menu.device_context_menu, popup.getMenu());
        popup.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == R.id.action_etp_explorer) {
                ((MainActivity) requireActivity()).showEtpExplorer(device);
                return true;
            }
            if (id == R.id.action_etp_search) {
                ((MainActivity) requireActivity()).showEtpSearch(device);
                return true;
            } else if (id == R.id.action_device_props) {
                ((MainActivity) requireActivity()).showAliasDialog(device);
                return true;
            }
            return false;
        });
        popup.show();
    }

    @Override
    public void onResume() {
        super.onResume();
        viewModel.refreshDevices();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        viewModel.setDeviceProgressListener(null);
    }
}