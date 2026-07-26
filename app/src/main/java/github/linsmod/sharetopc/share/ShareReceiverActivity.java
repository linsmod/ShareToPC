package github.linsmod.sharetopc.share;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
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

/** 接收 Sharesheet 的对话框式 Activity：解析 → 选设备/策略 → 发送 → 结束。 */
public class ShareReceiverActivity extends AppCompatActivity {

    private TextView tvContentCount;
    private ListView lvContentList;
    private TextView tvPreviewFallback;
    private ContentAdapter contentAdapter;
    private TextView tvNoDeviceHint;
    private Spinner spinnerDevices;
    private TextView tvPolicyTitle;
    private CheckBox cbCleanAfterSent;
    private Button btnSend;
    private Button btnStash;
    private Button btnCancel;

    private final List<DiscoveredDevice> devices = new ArrayList<>();
    private ArrayAdapter<DiscoveredDevice> deviceAdapter;
    private List<PendingContent> pendingItems;
    private boolean startedDiscoveryHere = false;

    private final NsdDiscoveryManager.Listener discoveryListener = list -> refreshDevices();

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.dialog_share);
        // 根布局自带 24dp margin，窗口撑满即可约 85% 视觉宽度
        android.view.Window window = getWindow();
        if (window != null) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }

        tvContentCount = findViewById(R.id.tvContentCount);
        lvContentList = findViewById(R.id.lvContentList);
        tvPreviewFallback = findViewById(R.id.tvPreviewFallback);
        contentAdapter = new ContentAdapter();
        lvContentList.setAdapter(contentAdapter);
        tvNoDeviceHint = findViewById(R.id.tvNoDeviceHint);
        spinnerDevices = findViewById(R.id.spinnerDevices);
        tvPolicyTitle = findViewById(R.id.tvPolicyTitle);
        cbCleanAfterSent = findViewById(R.id.cbCleanAfterSent);
        btnSend = findViewById(R.id.btnSend);
        btnStash = findViewById(R.id.btnStash);
        btnCancel = findViewById(R.id.btnCancel);

        deviceAdapter = new ArrayAdapter<DiscoveredDevice>(this,
                R.layout.item_spinner_selected, devices) {
            @NonNull
            @Override
            public View getView(int pos, @Nullable View convertView, @NonNull ViewGroup parent) {
                return bindView(pos, convertView, parent, R.layout.item_spinner_selected);
            }

            @Override
            public View getDropDownView(int pos, @Nullable View convertView,
                                        @NonNull ViewGroup parent) {
                return bindView(pos, convertView, parent, R.layout.item_spinner_dropdown);
            }

            private View bindView(int pos, View convertView, ViewGroup parent, int layoutId) {
                if (convertView == null) {
                    convertView = LayoutInflater.from(getContext())
                            .inflate(layoutId, parent, false);
                }
                DiscoveredDevice device = getItem(pos);
                View dot = convertView.findViewById(R.id.vDot);
                TextView tvName = convertView.findViewById(R.id.tvName);
                TextView tvAddr = convertView.findViewById(R.id.tvAddr);
                if (device != null) {
                    dot.setBackgroundResource(
                            device.isOnline ? R.drawable.dot_online : R.drawable.dot_offline);
                    tvName.setText(device.displayName());
                    if (tvAddr != null) {
                        tvAddr.setText(device.host + ":" + device.port);
                    }
                }
                return convertView;
            }
        };
        spinnerDevices.setAdapter(deviceAdapter);

        // 默认清理策略取设置值
        CleanupPolicy defaultPolicy = App.get().settingsStore.getCleanupPolicy();
        cbCleanAfterSent.setChecked(defaultPolicy != CleanupPolicy.NEVER);

        btnCancel.setOnClickListener(v -> finish());
        btnSend.setOnClickListener(v -> doSend());
        btnStash.setOnClickListener(v -> doStash());

        startDiscoveryIfPermitted();
        parseShareIntent();
    }

    private void parseShareIntent() {
        tvPreviewFallback.setVisibility(View.VISIBLE);
        tvPreviewFallback.setText(R.string.parsing_share);
        App.get().ioExecutor.execute(() -> {
            List<PendingContent> items = ShareParser.parse(getApplicationContext(), getIntent());
            runOnUiThread(() -> {
                tvPreviewFallback.setVisibility(View.GONE);
                if (items.isEmpty()) {
                    Toast.makeText(this, "没有可发送的内容", Toast.LENGTH_SHORT).show();
                    finish();
                    return;
                }
                pendingItems = items;
                // 填充可滚动列表
                contentAdapter.setItems(items);
                tvContentCount.setVisibility(View.VISIBLE);
                lvContentList.setVisibility(View.VISIBLE);
                tvContentCount.setText(buildCountSummary(items));
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
                cbCleanAfterSent.setVisibility(policyVis);
                updateSendEnabled();
            });
        });
    }

    private String buildCountSummary(List<PendingContent> items) {
        int fileCount = 0;
        int textCount = 0;
        long totalSize = 0;
        for (PendingContent item : items) {
            if (item.isText()) {
                textCount++;
            } else {
                fileCount++;
                totalSize += item.size;
            }
        }
        StringBuilder sb = new StringBuilder("共 ").append(items.size()).append(" 项");
        if (fileCount > 0 && textCount > 0) {
            sb.append("（").append(fileCount).append(" 个文件, ")
                    .append(textCount).append(" 段文本）");
        } else if (fileCount > 0) {
            sb.append("（").append(fileCount).append(" 个文件, ")
                    .append(github.linsmod.sharetopc.util.FileUtils.formatSize(totalSize))
                    .append("）");
        } else {
            sb.append("（").append(textCount).append(" 段文本）");
        }
        return sb.toString();
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

    /** 暂存：不发送，写入 PENDING 历史记录，文件通过 content:// URI 保留访问。 */
    private void doStash() {
        if (pendingItems == null || pendingItems.isEmpty()) {
            return;
        }
        CleanupPolicy policy = cbCleanAfterSent.isChecked() ? CleanupPolicy.AFTER_SEND : CleanupPolicy.NEVER;
        for (PendingContent item : pendingItems) {
            App.get().historyStore.add(
                    github.linsmod.sharetopc.data.TransferRecord.pending(item, policy.name()));
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

        // 自动选择：上次+在线 → 第一个在线 → 上次（离线）→ 第一项
        if (!devices.isEmpty()) {
            int sel = 0; // default: first
            String lastKey = App.get().settingsStore.getLastDeviceKey();
            int firstOnline = -1;
            int lastOffline = -1;
            for (int i = 0; i < devices.size(); i++) {
                DiscoveredDevice d = devices.get(i);
                if (d.isOnline) {
                    if (firstOnline < 0) firstOnline = i;
                    if (lastKey != null && d.key().equals(lastKey)) { sel = i; break; } // 上次+在线 → 直接选
                } else if (lastKey != null && d.key().equals(lastKey)) {
                    lastOffline = i;
                }
            }
            if (sel == 0) {
                if (firstOnline >= 0) sel = firstOnline;             // 无上次在线 → 第一个在线
                else if (lastOffline >= 0) sel = lastOffline;        // 全离线 → 上次的设备
            }
            spinnerDevices.setSelection(sel);
        }
    }

    private void updateSendEnabled() {
        boolean hasContent = pendingItems != null && !pendingItems.isEmpty();
        boolean hasDevice = !devices.isEmpty();
        btnSend.setEnabled(hasContent && hasDevice);
        // 有目标设备时不显示暂存，直接发送即可
        btnStash.setEnabled(hasContent);
        btnStash.setVisibility(hasDevice ? View.GONE : View.VISIBLE);
    }

    private void doSend() {
        DiscoveredDevice target = (DiscoveredDevice) spinnerDevices.getSelectedItem();
        if (target == null || pendingItems == null) {
            return;
        }
        App.get().settingsStore.saveLastDevice(target.host, target.port);
        CleanupPolicy policy = cbCleanAfterSent.isChecked() ? CleanupPolicy.AFTER_SEND : CleanupPolicy.NEVER;
        App.get().transferManager.enqueue(pendingItems,
                target.host, target.port, target.displayName(), policy);
        // 立即关闭窗口，传输在后台继续
        finish();
    }

    @Override
    protected void onDestroy() {
        App.get().discoveryManager.removeListener(discoveryListener);
        if (startedDiscoveryHere) {
            App.get().discoveryManager.stopDiscovery();
        }
        super.onDestroy();
    }

    // ---------- 内容列表适配器 ----------

    private class ContentAdapter extends BaseAdapter {

        private final List<PendingContent> items = new ArrayList<>();

        void setItems(List<PendingContent> newItems) {
            items.clear();
            items.addAll(newItems);
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public PendingContent getItem(int pos) {
            return items.get(pos);
        }

        @Override
        public long getItemId(int pos) {
            return pos;
        }

        @Override
        public View getView(int pos, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(ShareReceiverActivity.this)
                        .inflate(R.layout.item_share_content, parent, false);
            }
            PendingContent item = getItem(pos);
            View dot = convertView.findViewById(R.id.vContentTypeDot);
            TextView tvName = convertView.findViewById(R.id.tvContentName);
            TextView tvMeta = convertView.findViewById(R.id.tvContentMeta);

            if (item.isText()) {
                dot.setBackgroundResource(R.drawable.dot_content_text);
                String preview = item.text.length() > 40
                        ? item.text.substring(0, 40) + "…" : item.text;
                tvName.setText("文本：" + preview);
                tvMeta.setText(item.text.length() + " 字符");
            } else {
                dot.setBackgroundResource(R.drawable.dot_content_file);
                tvName.setText(item.name);
                tvMeta.setText(github.linsmod.sharetopc.util.FileUtils.formatSize(item.size));
            }
            return convertView;
        }
    }
}
