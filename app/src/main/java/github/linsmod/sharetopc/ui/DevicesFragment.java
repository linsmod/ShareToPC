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
                ((MainActivity) requireActivity()).showDeviceQueueDialog(device);
            }

            @Override
            public void onLongClick(DiscoveredDevice device) {
                ((MainActivity) requireActivity()).showAliasDialog(device);
            }
        });
        rvDevices.setAdapter(deviceAdapter);
        deviceAdapter.setOnAddDeviceClickListener(() ->
                ((MainActivity) requireActivity()).showManualDeviceDialog());

        btnRefreshDevices.setOnClickListener(v -> viewModel.startDiscovery());

        viewModel.getDevices().observe(getViewLifecycleOwner(), deviceAdapter::submitList);
        viewModel.getConnState().observe(getViewLifecycleOwner(), tvConnState::setText);
        viewModel.setDeviceProgressListener(deviceAdapter::notifyProgressChanged);

        return view;
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