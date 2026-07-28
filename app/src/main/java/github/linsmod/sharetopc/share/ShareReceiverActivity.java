package github.linsmod.sharetopc.share;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import github.linsmod.sharetopc.App;
import github.linsmod.sharetopc.R;
import github.linsmod.sharetopc.data.SendEntry;
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
    private Button btnSend;
    private Button btnCancel;

    private final List<DiscoveredDevice> devices = new ArrayList<>();
    private ArrayAdapter<DiscoveredDevice> deviceAdapter;
    private List<SendEntry> pendingItems;
    private String jobId;
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
        btnSend = findViewById(R.id.btnSend);
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

        btnCancel.setOnClickListener(v -> finish());
        btnSend.setOnClickListener(v -> doSend());

        startDiscovery();
        parseShareIntent();
    }

    private void parseShareIntent() {
        tvPreviewFallback.setVisibility(View.VISIBLE);
        tvPreviewFallback.setText(R.string.parsing_share);

        App.get().ioExecutor.execute(() -> {
            this.jobId = UUID.randomUUID().toString();
            List<SendEntry> items = ShareParser.parse(getApplicationContext(), getIntent());
            items.forEach(x->{
                x.jobId = jobId;
                App.get().jobStore.addJobEntry(x);
            });

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
                updateSendEnabled();
            });
        });
    }

    private String buildCountSummary(List<SendEntry> items) {
        int fileCount = 0;
        int textCount = 0;
        long totalSize = 0;
        for (SendEntry item : items) {
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

    private void startDiscovery() {
        App.get().discoveryManager.addListener(discoveryListener);
        startedDiscoveryHere = !App.get().discoveryManager.isDiscovering();
        App.get().discoveryManager.startDiscovery(
                App.get().settingsStore.getScanTimeoutSec() * 1000L);
        refreshDevices();
    }

    private void refreshDevices() {
        devices.clear();
        devices.addAll(App.get().discoveryManager.getDevices());
        DiscoveredDevice manual = App.get().settingsStore.getManualDevice();
        if (manual != null) {
            // 手动设备排最前，避免与自动发现重复
            boolean exists = false;
            for (DiscoveredDevice d : devices) {
                if (d.address().equals(manual.address())) {
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
                    if (lastKey != null && d.address().equals(lastKey)) { sel = i; break; } // 上次+在线 → 直接选
                } else if (lastKey != null && d.address().equals(lastKey)) {
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
    }

    private void doSend() {
        DiscoveredDevice target = (DiscoveredDevice) spinnerDevices.getSelectedItem();
        if (target == null || pendingItems == null) {
            return;
        }
        App.get().settingsStore.saveLastDevice(target.host, target.port);
        // 为每个文件预打开 PFD，获取内核文件句柄以支持可靠 skip/seek 断点续传
        // 同时绑定设备信息到 entry 对象并同步写入 DB
        for (SendEntry item : pendingItems) {
            item.host = target.host;
            item.port = target.port;
            item.targetName = target.displayName();
            App.get().jobStore.updateJobEntry(item.id, target.host, target.port,
                    target.displayName(), item.status, item.idleReason, item.errorMsg);
            var pfd = ShareParser.createPfd(getApplicationContext(), item);
            if(pfd!=null)
                App.get().transferManager.registerPfdIfNeeded(item.name,item.id,pfd);
        }
        if(jobId==null)
            throw new RuntimeException("Null of jobId");
        App.get().transferManager.enqueue(jobId, pendingItems,
                target.host, target.port, target.displayName());
        jobId = null;
        // 跳转主页面，传输在后台继续
        Intent intent = new Intent(this, github.linsmod.sharetopc.MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(intent);
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

        private final List<SendEntry> items = new ArrayList<>();

        void setItems(List<SendEntry> newItems) {
            items.clear();
            items.addAll(newItems);
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public SendEntry getItem(int pos) {
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
            SendEntry item = getItem(pos);
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
