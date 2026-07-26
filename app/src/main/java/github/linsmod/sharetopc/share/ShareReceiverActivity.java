package github.linsmod.sharetopc.share;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

import github.linsmod.sharetopc.App;
import github.linsmod.sharetopc.R;
import github.linsmod.sharetopc.cleanup.CleanupPolicy;
import github.linsmod.sharetopc.data.PendingContent;
import github.linsmod.sharetopc.discovery.DiscoveredDevice;
import github.linsmod.sharetopc.discovery.NsdDiscoveryManager;
import github.linsmod.sharetopc.transfer.TransferListener;
import github.linsmod.sharetopc.transfer.TransferManager;

/** 接收 Sharesheet 的对话框式 Activity：解析 → 选设备/策略 → 发送 → 结束。 */
public class ShareReceiverActivity extends AppCompatActivity {

    private TextView tvPreview;
    private TextView tvNoDeviceHint;
    private TextView tvSendStatus;
    private Spinner spinnerDevices;
    private TextView tvPolicyTitle;
    private android.widget.RadioGroup rgPolicy;
    private RadioButton rbAfterSend;
    private RadioButton rbNever;
    private ProgressBar progressSend;
    private Button btnSend;
    private Button btnStash;
    private Button btnCancel;

    private final List<DiscoveredDevice> devices = new ArrayList<>();
    private ArrayAdapter<DiscoveredDevice> deviceAdapter;
    private List<PendingContent> pendingItems;
    private String currentJobId;
    private boolean jobRunning = false;
    private boolean startedDiscoveryHere = false;

    private final NsdDiscoveryManager.Listener discoveryListener = list -> refreshDevices();
    private final TransferListener transferListener = new TransferListener() {
        @Override
        public void onStateChanged(TransferManager.State state, String jobName) {
            if (jobRunning) {
                showStatus(stateText(state));
            }
        }

        @Override
        public void onProgress(String itemName, long sentBytes, long totalBytes) {
            if (jobRunning && totalBytes > 0) {
                progressSend.setProgress((int) (sentBytes * 100 / totalBytes));
            }
        }

        @Override
        public void onJobFinished(String jobId, boolean success, String errorMsg) {
            if (!jobId.equals(currentJobId)) {
                return;
            }
            jobRunning = false;
            if (success) {
                Toast.makeText(ShareReceiverActivity.this,
                        R.string.send_success, Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(ShareReceiverActivity.this,
                        getString(R.string.send_failed, errorMsg), Toast.LENGTH_LONG).show();
            }
            finish();
        }

        @Override
        public void onHistoryChanged() {
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.dialog_share);

        tvPreview = findViewById(R.id.tvPreview);
        tvNoDeviceHint = findViewById(R.id.tvNoDeviceHint);
        tvSendStatus = findViewById(R.id.tvSendStatus);
        spinnerDevices = findViewById(R.id.spinnerDevices);
        tvPolicyTitle = findViewById(R.id.tvPolicyTitle);
        rgPolicy = findViewById(R.id.rgPolicy);
        rbAfterSend = findViewById(R.id.rbAfterSend);
        rbNever = findViewById(R.id.rbNever);
        progressSend = findViewById(R.id.progressSend);
        btnSend = findViewById(R.id.btnSend);
        btnStash = findViewById(R.id.btnStash);
        btnCancel = findViewById(R.id.btnCancel);

        deviceAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, devices);
        spinnerDevices.setAdapter(deviceAdapter);

        // 默认清理策略取设置值
        CleanupPolicy defaultPolicy = App.get().settingsStore.getCleanupPolicy();
        (defaultPolicy == CleanupPolicy.NEVER ? rbNever : rbAfterSend).setChecked(true);

        btnCancel.setOnClickListener(v -> finish());
        btnSend.setOnClickListener(v -> doSend());
        btnStash.setOnClickListener(v -> doStash());

        App.get().transferManager.addListener(transferListener);
        startDiscoveryIfPermitted();
        parseShareIntent();
    }

    private void parseShareIntent() {
        tvPreview.setText(R.string.parsing_share);
        App.get().ioExecutor.execute(() -> {
            List<PendingContent> items = ShareParser.parse(getApplicationContext(), getIntent());
            runOnUiThread(() -> {
                if (items.isEmpty()) {
                    Toast.makeText(this, "没有可发送的内容", Toast.LENGTH_SHORT).show();
                    finish();
                    return;
                }
                pendingItems = items;
                tvPreview.setText(buildPreview(items));
                // 清理策略仅对文件类型内容显示（生效层在 CleanupScheduler 已按类型过滤）
                boolean hasFile = false;
                for (PendingContent item : items) {
                    if (!item.isText()) {
                        hasFile = true;
                        break;
                    }
                }
                int policyVis = hasFile ? View.VISIBLE : View.GONE;
                tvPolicyTitle.setVisibility(policyVis);
                rgPolicy.setVisibility(policyVis);
                updateSendEnabled();
            });
        });
    }

    private String buildPreview(List<PendingContent> items) {
        StringBuilder sb = new StringBuilder();
        for (PendingContent item : items) {
            if (item.isText()) {
                String text = item.text.length() > 50
                        ? item.text.substring(0, 50) + "…" : item.text;
                sb.append("文本：").append(text);
            } else {
                sb.append("文件：").append(item.name)
                        .append("（").append(github.linsmod.sharetopc.util.FileUtils
                                .formatSize(item.size)).append("）");
            }
            sb.append('\n');
        }
        return sb.toString().trim();
    }

    private void startDiscoveryIfPermitted() {
        boolean granted = ContextCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        if (granted) {
            App.get().discoveryManager.addListener(discoveryListener);
            startedDiscoveryHere = !App.get().discoveryManager.isDiscovering();
            App.get().discoveryManager.startDiscovery(
                    App.get().settingsStore.getScanTimeoutSec() * 1000L);
        }
        refreshDevices();
    }

    /** 暂存：不发送，写入 PENDING 历史记录，文件保留在 inbox 等待补发。 */
    private void doStash() {
        if (pendingItems == null || pendingItems.isEmpty()) {
            return;
        }
        CleanupPolicy policy = rbNever.isChecked() ? CleanupPolicy.NEVER : CleanupPolicy.AFTER_SEND;
        for (PendingContent item : pendingItems) {
            App.get().historyStore.add(
                    github.linsmod.sharetopc.data.TransferRecord.pending(item, policy.name()));
            if (!item.isText() && item.localPath != null) {
                // 标记为“不自动清理”，等待补发后再按所选策略处理
                github.linsmod.sharetopc.cleanup.CleanupScheduler.putMeta(
                        this, item.localPath, CleanupPolicy.NEVER, 0L);
            }
        }
        Toast.makeText(this, R.string.stashed, Toast.LENGTH_LONG).show();
        finish();
    }

    private void refreshDevices() {
        devices.clear();
        devices.addAll(App.get().discoveryManager.getDevices());
        DiscoveredDevice manual = App.get().settingsStore.getManualDevice();
        if (manual != null) {
            // 手动设备排最前，避免与自动发现重复
            boolean exists = false;
            for (DiscoveredDevice d : devices) {
                if (d.key().equals(manual.key())) {
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                devices.add(0, manual);
            }
        }
        deviceAdapter.notifyDataSetChanged();
        boolean noDevice = devices.isEmpty();
        tvNoDeviceHint.setVisibility(noDevice ? View.VISIBLE : View.GONE);
        updateSendEnabled();
    }

    private void updateSendEnabled() {
        boolean hasContent = pendingItems != null && !pendingItems.isEmpty();
        btnSend.setEnabled(hasContent && !devices.isEmpty() && !jobRunning);
        btnStash.setEnabled(hasContent && !jobRunning);
    }

    private void doSend() {
        DiscoveredDevice target = (DiscoveredDevice) spinnerDevices.getSelectedItem();
        if (target == null || pendingItems == null) {
            return;
        }
        CleanupPolicy policy = rbNever.isChecked() ? CleanupPolicy.NEVER : CleanupPolicy.AFTER_SEND;
        jobRunning = true;
        progressSend.setVisibility(View.VISIBLE);
        progressSend.setProgress(0);
        tvSendStatus.setVisibility(View.VISIBLE);
        btnSend.setEnabled(false);
        btnCancel.setEnabled(false);
        currentJobId = App.get().transferManager.enqueue(pendingItems,
                target.host, target.port, target.displayName(), policy);
    }

    private void showStatus(String text) {
        tvSendStatus.setText(text);
    }

    private String stateText(TransferManager.State state) {
        switch (state) {
            case CONNECTING:
                return getString(R.string.state_connecting);
            case SENDING:
                return getString(R.string.state_sending);
            case DONE:
                return getString(R.string.state_done);
            case FAILED:
                return getString(R.string.state_failed);
            default:
                return "";
        }
    }

    @Override
    protected void onDestroy() {
        App.get().transferManager.removeListener(transferListener);
        App.get().discoveryManager.removeListener(discoveryListener);
        if (startedDiscoveryHere) {
            App.get().discoveryManager.stopDiscovery();
        }
        super.onDestroy();
    }
}
