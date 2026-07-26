package github.linsmod.sharetopc;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.InputType;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import github.linsmod.sharetopc.data.SettingsStore;
import github.linsmod.sharetopc.ui.DeviceAdapter;
import github.linsmod.sharetopc.ui.HistoryAdapter;
import github.linsmod.sharetopc.ui.SettingsActivity;

public class MainActivity extends AppCompatActivity {

    private MainViewModel viewModel;
    private TextView tvConnState;
    private TextView tvProgress;
    private ProgressBar progressBar;
    private DeviceAdapter deviceAdapter;
    private HistoryAdapter historyAdapter;

    private TextView tvDevicesTitle;
    private TextView tvSearchingStatus;
    private RecyclerView rvDevices;
    private View btnAddManual;
    private View progressSection;
    private android.widget.Button btnExpandHistory;
    private View selectionBar;
    private TextView tvHistoryTitle;
    private View mainContent;

    // 自定义抽屉拖拽
    private View historySheet;
    private int sheetExpandedTop;
    private int sheetCollapsedTop;
    private boolean sheetExpanded = false;
    private boolean sheetDragging = false;
    private float lastTouchY;

    private final ActivityResultLauncher<String> locationPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) {
                    viewModel.startDiscovery();
                } else {
                    viewModel.setPermissionDeniedState();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        final int[] coordinatorAvailableHeight = {0};
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        viewModel = new ViewModelProvider(this).get(MainViewModel.class);

        mainContent = findViewById(R.id.mainContent);
        tvConnState = findViewById(R.id.tvConnState);
        tvProgress = findViewById(R.id.tvProgress);
        progressBar = findViewById(R.id.progressBar);

        tvDevicesTitle = findViewById(R.id.tvDevicesTitle);
        tvSearchingStatus = findViewById(R.id.tvSearchingStatus);
        progressSection = findViewById(R.id.progressSection);
        btnAddManual = findViewById(R.id.btnAddManual);
        btnExpandHistory = findViewById(R.id.btnExpandHistory);
        selectionBar = findViewById(R.id.selectionBar);
        tvHistoryTitle = findViewById(R.id.tvHistoryTitle);

        // 底部抽屉：自定义拖拽实现
        historySheet = findViewById(R.id.historySheet);
        View sheetHeader = findViewById(R.id.sheetHeader);

        // 获取状态栏高度，计算停靠位置
        int statusBarHeight = 0;
        int resId = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (resId > 0) {
            statusBarHeight = getResources().getDimensionPixelSize(resId);
        }
        int parentHeight = getResources().getDisplayMetrics().heightPixels;
        int peekHeight = (parentHeight - statusBarHeight) / 3;
        sheetExpandedTop = statusBarHeight;
        sheetCollapsedTop = parentHeight - peekHeight;

        // 初始定位到折叠态（post 确保布局完成后再定位）
        historySheet.post(() -> historySheet.setY(sheetCollapsedTop));

        // 头部区域响应拖拽
        sheetHeader.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    lastTouchY = event.getRawY();
                    sheetDragging = false;
                    v.getParent().requestDisallowInterceptTouchEvent(true);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dy = event.getRawY() - lastTouchY;
                    if (Math.abs(dy) > 5) {
                        sheetDragging = true;
                    }
                    if (sheetDragging) {
                        float newY = historySheet.getY() + dy;
                        newY = Math.max(sheetExpandedTop, Math.min(sheetCollapsedTop, newY));
                        historySheet.setY(newY);
                        lastTouchY = event.getRawY();
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (sheetDragging) {
                        float mid = (sheetExpandedTop + sheetCollapsedTop) / 2f;
                        float target = historySheet.getY() < mid ? sheetExpandedTop : sheetCollapsedTop;
                        snapSheet(target);
                    }
                    return true;
            }
            return false;
        });

        // 按钮切换展开/折叠
        btnExpandHistory.setOnClickListener(v -> {
            if (sheetExpanded) {
                snapSheet(sheetCollapsedTop);
            } else {
                snapSheet(sheetExpandedTop);
            }
        });

        rvDevices = findViewById(R.id.rvDevices);
        rvDevices.setLayoutManager(new LinearLayoutManager(this));
        deviceAdapter = new DeviceAdapter(new DeviceAdapter.OnDeviceClickListener() {
            @Override
            public void onClick(github.linsmod.sharetopc.discovery.DiscoveredDevice device) {
                Toast.makeText(MainActivity.this, device.toString(), Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onLongClick(github.linsmod.sharetopc.discovery.DiscoveredDevice device) {
                showAliasDialog(device);
            }
        });
        rvDevices.setAdapter(deviceAdapter);

        RecyclerView rvHistory = findViewById(R.id.rvHistory);
        rvHistory.setLayoutManager(new LinearLayoutManager(this));
        historyAdapter = new HistoryAdapter(new HistoryAdapter.ActionListener() {
            @Override
            public void onRetry(github.linsmod.sharetopc.data.TransferRecord record) {
                viewModel.retry(record);
            }

            @Override
            public void onSendPending(github.linsmod.sharetopc.data.TransferRecord record) {
                showDevicePickerForPending(java.util.Collections.singletonList(record));
            }

            @Override
            public void onSelectionChanged(int count) {
                updateSelectionBar(count);
            }

            @Override
            public void onItemClick(github.linsmod.sharetopc.data.TransferRecord record) {
                if (record.contentType == github.linsmod.sharetopc.data.PendingContent.TYPE_TEXT
                        && record.textContent != null) {
                    showTextPreview(record);
                }
            }
        });
        rvHistory.setAdapter(historyAdapter);

        findViewById(R.id.btnSettings).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));
        btnAddManual.setOnClickListener(v -> showManualDeviceDialog());

        View btnRefreshDevices = findViewById(R.id.btnRefreshDevices);
        btnRefreshDevices.setOnClickListener(v -> viewModel.startDiscovery());
        viewModel.getScanning().observe(this, isScanning -> {
            boolean scanning = Boolean.TRUE.equals(isScanning);
            tvConnState.setVisibility(scanning ? View.GONE : View.VISIBLE);
            btnRefreshDevices.setVisibility(scanning ? View.GONE : View.VISIBLE);
            tvSearchingStatus.setVisibility(scanning ? View.VISIBLE : View.GONE);
        });
        findViewById(R.id.btnSelectAll).setOnClickListener(v -> historyAdapter.toggleSelectAll());
        findViewById(R.id.btnResendSelected).setOnClickListener(v -> resendSelected());
        findViewById(R.id.btnDeleteSelected).setOnClickListener(v -> confirmDeleteSelected());
        findViewById(R.id.btnCancelSelection).setOnClickListener(v -> historyAdapter.exitSelectionMode());

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (historyAdapter.isSelectionMode()) {
                    historyAdapter.exitSelectionMode();
                } else if (sheetExpanded) {
                    snapSheet(sheetCollapsedTop);
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });

        viewModel.getDevices().observe(this, deviceAdapter::submitList);
        viewModel.getConnState().observe(this, tvConnState::setText);
        viewModel.getSearchHint().observe(this, tvSearchingStatus::setText);
        viewModel.getDeviceTransfer().observe(this, deviceAdapter::setCurrentTransfer);
        viewModel.getHistory().observe(this, historyAdapter::submitList);
        viewModel.getProgress().observe(this, p -> {
            if (p == null) {
                tvProgress.setText(R.string.current_transfer_idle);
                progressBar.setProgress(0);
            } else if (p.percent >= 0) {
                tvProgress.setText(p.statusText + " " + p.name + " " + p.percent + "%");
                progressBar.setProgress(p.percent);
            } else {
                tvProgress.setText(p.statusText + " " + p.name);
            }
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            viewModel.startDiscovery();
        } else {
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION);
        }
    }

    @Override
    protected void onStop() {
        viewModel.stopDiscovery();
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        viewModel.refreshHistory();
    }

    /** 文本记录预览：完整内容可选中，提供复制。 */
    private void showTextPreview(github.linsmod.sharetopc.data.TransferRecord record) {
        TextView tv = new TextView(this);
        tv.setText(record.textContent);
        tv.setTextIsSelectable(true);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        tv.setPadding(pad, pad / 2, pad, 0);

        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(tv);

        new AlertDialog.Builder(this)
                .setTitle(R.string.text_preview_title)
                .setView(scroll)
                .setPositiveButton(R.string.copy, (dialog, which) -> {
                    android.content.ClipboardManager cm =
                            (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(android.content.ClipData.newPlainText(
                            getString(R.string.text_preview_title), record.textContent));
                    Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show();
                })
                .setNeutralButton(R.string.share, (dialog, which) -> {
                    Intent sendIntent = new Intent(Intent.ACTION_SEND);
                    sendIntent.setType("text/plain");
                    sendIntent.putExtra(Intent.EXTRA_TEXT, record.textContent);
                    startActivity(Intent.createChooser(sendIntent, getString(R.string.share)));
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** 批量重发：有目标的直接重发；暂存记录弹设备选择器统一补发；无法投递的跳过。 */
    private void resendSelected() {
        java.util.List<github.linsmod.sharetopc.data.TransferRecord> selected =
                historyAdapter.getSelectedRecords();
        if (selected.isEmpty()) {
            return;
        }

        java.util.List<github.linsmod.sharetopc.data.TransferRecord> pendingList =
                new java.util.ArrayList<>();
        int resent = 0;
        int skipped = 0;
        for (github.linsmod.sharetopc.data.TransferRecord r : selected) {
            if (!r.canDeliver()) {
                skipped++;
            } else if (r.isPending() || r.host == null) {
                pendingList.add(r);
            } else {
                viewModel.retry(r);
                resent++;
            }
        }
        historyAdapter.exitSelectionMode();

        StringBuilder sb = new StringBuilder();
        if (resent > 0) {
            sb.append(getString(R.string.resent_count, resent));
        }
        if (skipped > 0) {
            if (sb.length() > 0) {
                sb.append("；");
            }
            sb.append(getString(R.string.resend_skipped, skipped));
        }
        if (sb.length() > 0) {
            Toast.makeText(this, sb.toString(), Toast.LENGTH_LONG).show();
        }

        if (!pendingList.isEmpty()) {
            showDevicePickerForPending(pendingList);
        }
    }

    /** 多选操作条显隐：标题显示"传输历史 (已选 N 项)"，选择栏显示/隐藏。 */
    private void updateSelectionBar(int count) {
        if (historyAdapter.isSelectionMode()) {
                tvHistoryTitle.setText(getString(R.string.history_title) + " (" + getString(R.string.selected_count, count) + ")");
            selectionBar.setVisibility(View.VISIBLE);
        } else {
            tvHistoryTitle.setText(R.string.history_title);
            selectionBar.setVisibility(View.GONE);
        }
    }

    /** 删除确认弹窗，确认后批量删除选中记录。 */
    private void confirmDeleteSelected() {
        final java.util.List<String> ids = historyAdapter.getSelectedIds();
        if (ids.isEmpty()) {
            return;
        }
        new AlertDialog.Builder(this)
                .setMessage(getString(R.string.delete_selected_confirm, ids.size()))
                .setPositiveButton(R.string.delete, (dialog, which) -> {
                    viewModel.deleteRecords(ids);
                    historyAdapter.exitSelectionMode();
                    Toast.makeText(this, getString(R.string.records_deleted, ids.size()),
                            Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** 抽屉吸附到目标位置 */
    private void snapSheet(float targetY) {
        sheetExpanded = (targetY == sheetExpandedTop);
        btnExpandHistory.setText(sheetExpanded ? R.string.history_restore : R.string.history_fullscreen);
        historySheet.animate()
                .y(targetY)
                .setDuration(250)
                .setInterpolator(new DecelerateInterpolator())
                .start();
    }

    /** 为暂存记录选择目标设备后补发（支持批量）。 */
    private void showDevicePickerForPending(
            java.util.List<github.linsmod.sharetopc.data.TransferRecord> records) {
        java.util.List<github.linsmod.sharetopc.discovery.DiscoveredDevice> targets =
                new java.util.ArrayList<>(App.get().discoveryManager.getDevices());
        github.linsmod.sharetopc.discovery.DiscoveredDevice manual =
                App.get().settingsStore.getManualDevice();
        if (manual != null) {
            boolean exists = false;
            for (github.linsmod.sharetopc.discovery.DiscoveredDevice d : targets) {
                if (d.key().equals(manual.key())) {
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                targets.add(0, manual);
            }
        }
        if (targets.isEmpty()) {
            Toast.makeText(this, R.string.no_device_for_send, Toast.LENGTH_LONG).show();
            return;
        }
        String[] names = new String[targets.size()];
        for (int i = 0; i < targets.size(); i++) {
            names[i] = targets.get(i).toString();
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.choose_device)
                .setItems(names, (dialog, which) -> {
                    for (github.linsmod.sharetopc.data.TransferRecord r : records) {
                        viewModel.sendPending(r, targets.get(which));
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** 设备备注名对话框：支持自由输入与常用预设，可恢复默认。 */
    private void showAliasDialog(github.linsmod.sharetopc.discovery.DiscoveredDevice device) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad / 2, pad, 0);

        EditText etAlias = new EditText(this);
        etAlias.setHint(device.name);
        etAlias.setInputType(InputType.TYPE_CLASS_TEXT);
        if (device.alias != null) {
            etAlias.setText(device.alias);
        }
        layout.addView(etAlias);

        // 常用预设：点击直接填入
        LinearLayout presets = new LinearLayout(this);
        presets.setOrientation(LinearLayout.HORIZONTAL);
        String[] presetNames = {
                getString(R.string.preset_laptop),
                getString(R.string.preset_office),
                getString(R.string.preset_desktop)
        };
        for (String preset : presetNames) {
            android.widget.Button btn = new android.widget.Button(this, null,
                    android.R.attr.borderlessButtonStyle);
            btn.setText(preset);
            btn.setTextSize(12f);
            btn.setPadding(0, 0, 0, 0);
            btn.setOnClickListener(v -> etAlias.setText(preset));
            presets.addView(btn);
        }
        layout.addView(presets);

        new AlertDialog.Builder(this)
                .setTitle(R.string.device_alias_title)
                .setView(layout)
                .setPositiveButton(R.string.save, (dialog, which) -> {
                    App.get().settingsStore.setDeviceAlias(device.host, device.port,
                            etAlias.getText().toString());
                    viewModel.refreshDevices();
                })
                .setNegativeButton(R.string.cancel, null)
                .setNeutralButton(R.string.reset_default, (dialog, which) -> {
                    App.get().settingsStore.setDeviceAlias(device.host, device.port, null);
                    viewModel.refreshDevices();
                })
                .show();
    }

    /** 手动输入 IP:端口 对话框，保存到设置。 */
    private void showManualDeviceDialog() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad / 2, pad, 0);

        EditText etHost = new EditText(this);
        etHost.setHint(R.string.manual_host_hint);
        etHost.setInputType(InputType.TYPE_CLASS_TEXT);
        etHost.setText(App.get().settingsStore.getManualHost());
        layout.addView(etHost);

        EditText etPort = new EditText(this);
        etPort.setHint(R.string.manual_port_hint);
        etPort.setInputType(InputType.TYPE_CLASS_NUMBER);
        etPort.setText(String.valueOf(App.get().settingsStore.getManualPort()));
        layout.addView(etPort);

        new AlertDialog.Builder(this)
                .setTitle(R.string.manual_device_dialog_title)
                .setView(layout)
                .setPositiveButton(R.string.ok, (dialog, which) -> {
                    String host = etHost.getText().toString().trim();
                    String port = etPort.getText().toString().trim();
                    App.get().settingsStore.saveManualDevice(host, port);
                    Toast.makeText(this, R.string.ok, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }
}
