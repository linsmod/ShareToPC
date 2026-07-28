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
        void onPauseRequested(TransferRecord record);
        /** 用户请求继续/重试一条记录。 */
        void onResumeRequested(TransferRecord record);
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

        findViewById(R.id.chipGroup).setVisibility(View.GONE);

        rvTaskList = findViewById(R.id.rvTaskList);
        rvTaskList.setLayoutManager(new LinearLayoutManager(getContext()));
        adapter = new DeviceTaskAdapter();
        rvTaskList.setAdapter(adapter);

        tvEmpty = findViewById(R.id.tvEmpty);

        historyLiveData.observe(lifecycleOwner, ignored -> refreshTaskList());
    }

    private void refreshTaskList() {
        String deviceKey = device.address();
        List<TransferRecord> list = historyLiveData.getValue();
        List<TransferRecord> scopedItems = new ArrayList<>();

        for (TransferRecord tr : list != null ? list : Collections.<TransferRecord>emptyList()) {
            if (tr.host == null) continue;
            if (!deviceKey.equals(tr.deviceAddress())) continue;
            scopedItems.add(tr);
        }

        if (scopedItems.isEmpty()) {
            rvTaskList.setVisibility(View.GONE);
            tvEmpty.setVisibility(View.VISIBLE);
        } else {
            rvTaskList.setVisibility(View.VISIBLE);
            tvEmpty.setVisibility(View.GONE);
        }
        adapter.submitList(scopedItems);
    }
}
