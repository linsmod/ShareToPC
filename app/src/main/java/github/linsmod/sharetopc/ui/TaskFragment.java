package github.linsmod.sharetopc.ui;

import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.tabs.TabLayout;

import java.util.ArrayList;
import java.util.List;

import github.linsmod.sharetopc.App;
import github.linsmod.sharetopc.MainViewModel;
import github.linsmod.sharetopc.R;
import github.linsmod.sharetopc.data.SendEntry;
import github.linsmod.sharetopc.discovery.DiscoveredDevice;
import github.linsmod.sharetopc.util.TransferStatusHelper;

public class TaskFragment extends Fragment {

    /** Fragment 参数 Key：按设备筛选传输任务。 */
    public static final String EXTRA_HOST = "host";
    public static final String EXTRA_PORT = "port";
    public static final String EXTRA_DEVICE_NAME = "deviceName";
    public static final String EXTRA_DEVICE_ID = "deviceId";

    /** 设备操作回调：由宿主 Activity 实现（MainActivity / DeviceTasksActivity）。 */
    public interface DeviceActionListener {
        void showEtpExplorer(DiscoveredDevice device);
        void showEtpSearch(DiscoveredDevice device);
    }

    private MainViewModel viewModel;
    private TaskAdapter taskAdapter;
    private DeviceActionListener deviceActionListener;
    private TextView tvTaskEmpty;

    /** 设备筛选参数（null 表示显示全部任务）。 */
    @Nullable private String filterHost;
    private int filterPort;
    @Nullable private String filterDeviceName;
    @Nullable private String filterDeviceId;

    /** 类型 tab：0=发送, 1=下载。 */
    private int taskTypeTab;
    @Nullable private List<TaskGroup> latestTasks;

    /** 任务变更回调（注册时立即收到当前值，避免 LiveData lifecycle 延迟）。 */
    private final MainViewModel.TasksChangeListener tasksListener = tasks -> {
        latestTasks = tasks;
        refreshList();
    };

    /** 条目进度增量更新回调。 */
    private final MainViewModel.EntryUpdateListener entryUpdateListener = entry -> {
        if (taskAdapter != null) {
            taskAdapter.onEntryProgressChanged(entry);
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_task, container, false);
        viewModel = new ViewModelProvider(requireActivity()).get(MainViewModel.class);

        // 读取设备筛选参数
        Bundle args = getArguments();
        if (args != null) {
            filterHost = args.getString(EXTRA_HOST);
            filterPort = args.getInt(EXTRA_PORT, 0);
            filterDeviceName = args.getString(EXTRA_DEVICE_NAME);
            filterDeviceId = args.getString(EXTRA_DEVICE_ID);
        }

        TextView tvTaskTitle = view.findViewById(R.id.tvTaskTitle);
        RecyclerView rvTask = view.findViewById(R.id.rvTask);
        TabLayout tabTaskType = view.findViewById(R.id.tabTaskType);
        tabTaskType.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override public void onTabSelected(TabLayout.Tab tab) {
                taskTypeTab = tab.getPosition();
                refreshList();
            }
            @Override public void onTabUnselected(TabLayout.Tab tab) {}
            @Override public void onTabReselected(TabLayout.Tab tab) {}
        });

        // 顶部工具栏已显示设备名（DeviceTasksActivity），标题统一为"传输任务"
        tvTaskTitle.setText(R.string.task_title);
        tvTaskEmpty = view.findViewById(R.id.tvTaskEmpty);

        rvTask.setLayoutManager(new LinearLayoutManager(requireContext()));
        rvTask.setItemAnimator(null); // 禁用动画，避免频繁 notifyItemChanged 导致闪烁
        taskAdapter = new TaskAdapter(new Listeners.TaskActionListener() {

            @Override
            public void onGroupMenuRequested(TaskGroup group, View anchor, float touchX) {
                showGroupMenu(group, anchor, touchX);
            }

            @Override
            public void onPullEntryMenuRequested(SendEntry entry, View anchor) {
                showPullEntryMenu(entry, anchor);
            }

            @Override
            public void onEntryMenuRequested(SendEntry entry, TaskGroup group, View anchor) {
                showEntryMenu(entry, group, anchor);
            }
        }, transferId -> viewModel.hasPfd(transferId));
        rvTask.setAdapter(taskAdapter);

        setupDeviceActions(view);

        FloatingActionButton fabClearTasks = view.findViewById(R.id.fabClearTasks);
        fabClearTasks.setOnClickListener(v -> showClearTasksDialog());

        viewModel.addTasksChangeListener(tasksListener);
        viewModel.addEntryUpdateListener(entryUpdateListener);

        return view;
    }

    private boolean isFiltered() {
        return filterHost != null && !filterHost.isEmpty();
    }

    @Override
    public void onAttach(@NonNull android.content.Context context) {
        super.onAttach(context);
        if (context instanceof DeviceActionListener) {
            deviceActionListener = (DeviceActionListener) context;
        }
    }

    /** 仅当按设备筛选时展示设备操作按钮（ETP 浏览/搜索、设备属性）。 */
    private void setupDeviceActions(@NonNull View view) {
        View rowDeviceActions = view.findViewById(R.id.rowDeviceActions);
        if (!isFiltered() || deviceActionListener == null) return;

        DiscoveredDevice device = new DiscoveredDevice(filterDeviceName, filterHost, filterPort);
        device.deviceId = filterDeviceId;
        device.alias = App.get().settingsStore.getDeviceAlias(filterHost, filterPort);

        rowDeviceActions.setVisibility(View.VISIBLE);
        view.findViewById(R.id.btnEtpExplorer).setOnClickListener(v ->
                deviceActionListener.showEtpExplorer(device));
        view.findViewById(R.id.btnEtpSearch).setOnClickListener(v ->
                deviceActionListener.showEtpSearch(device));
    }

    /** 根据设备筛选参数过滤任务分组，并隐藏分组内的设备信息文本。 */
    @NonNull
    private List<TaskGroup> applyFilter(@NonNull List<TaskGroup> tasks) {
        if (!isFiltered()) return tasks;
        String address = filterHost + ":" + filterPort;
        List<TaskGroup> result = new ArrayList<>();
        for (TaskGroup g : tasks) {
            if (address.equals(g.deviceAddress())) {
                g.showDeviceInfo = false; // 标题已有设备名，分组内不再重复
                result.add(g);
            }
        }
        return result;
    }

    /** 重新计算（设备 + 类型 tab）并刷新列表与空态提示。 */
    private void refreshList() {
        if (taskAdapter == null || getView() == null || latestTasks == null) return;
        List<TaskGroup> filtered = applyFilter(latestTasks);
        filtered = applyTypeFilter(filtered);
        taskAdapter.submitList(filtered);
        boolean empty = filtered.isEmpty();
        tvTaskEmpty.setText(emptyTextForTab());
        tvTaskEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
    }

    /** 按类型 tab 过滤：0=仅发送, 1=仅下载。 */
    @NonNull
    private List<TaskGroup> applyTypeFilter(@NonNull List<TaskGroup> tasks) {
        boolean wantPull = taskTypeTab == 1;
        List<TaskGroup> result = new ArrayList<>();
        for (TaskGroup g : tasks) {
            if (g.isPull() == wantPull) result.add(g);
        }
        return result;
    }

    private int emptyTextForTab() {
        return taskTypeTab == 1 ? R.string.task_empty_pull : R.string.task_empty_send;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        viewModel.removeTasksChangeListener(tasksListener);
        viewModel.removeEntryUpdateListener(entryUpdateListener);
    }

    @Override
    public void onResume() {
        super.onResume();
        viewModel.refreshTasks();
    }

    private void showClearTasksDialog() {
        // 当前 tab：0=发送, 1=下载 → 只清空对应方向的记录
        boolean pull = taskTypeTab == 1;
        int direction = pull ? SendEntry.DIRECTION_PULL : SendEntry.DIRECTION_SEND;
        String label = getString(pull ? R.string.task_tab_pull : R.string.task_tab_send);
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.task_clear_title)
                .setMessage(getString(R.string.task_clear_message, label))
                .setPositiveButton(R.string.ok, (d, which) -> {
                    App.get().jobStore.clearByDirection(direction);
                    viewModel.refreshHistory();
                    Toast.makeText(requireContext(),
                            getString(R.string.task_cleared_format, label),
                            Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void showGroupMenu(TaskGroup group, View anchor, float touchX) {
        PopupMenu popup = new PopupMenu(requireActivity(), anchor);
        popup.getMenuInflater().inflate(R.menu.group_context_menu, popup.getMenu());

        // 根据触摸横向位置安排菜单对齐方向：按在右半边则菜单右对齐
        int gravity = touchX > anchor.getWidth() * 0.5f ? Gravity.END : Gravity.START;
        popup.setGravity(gravity);

        // 根据分组状态启用/禁用菜单项
        boolean hasActive = group.hasActive();
        boolean hasResumable = false;
        for (SendEntry r : group.entries) {
            if (r.status == SendEntry.STATUS_IDLE
                    && r.idleReason != SendEntry.IDLE_REASON_SUCCESS
                    && r.pendingAction == 0
                    && github.linsmod.sharetopc.util.TransferStatusHelper.canResumeOrRetry(r,
                            transferId -> viewModel.hasPfd(transferId))) {
                hasResumable = true;
                break;
            }
        }

        popup.getMenu().findItem(R.id.action_pause_group).setEnabled(hasActive);
        popup.getMenu().findItem(R.id.action_resume_group).setEnabled(hasResumable && !hasActive);

        popup.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == R.id.action_pause_group) {
                viewModel.pauseGroup(group);
                Toast.makeText(requireContext(), "已暂停传输", Toast.LENGTH_SHORT).show();
                return true;
            } else if (id == R.id.action_resume_group) {
                viewModel.resumeGroup(group);
                return true;
            } else if (id == R.id.action_delete_group) {
                viewModel.deleteGroup(group);
                Toast.makeText(requireContext(),
                        getString(R.string.group_deleted, group.totalEntries),
                        Toast.LENGTH_SHORT).show();
                return true;
            }
            return false;
        });

        popup.show();
    }

    /** 单个条目菜单：暂停/恢复/删除（与分组菜单逻辑一致）。 */
    private void showEntryMenu(SendEntry entry, TaskGroup group, View anchor) {
        PopupMenu popup = new PopupMenu(requireActivity(), anchor);
        popup.getMenuInflater().inflate(R.menu.group_context_menu, popup.getMenu());

        boolean isActive = entry.status == SendEntry.STATUS_SENDING;
        boolean canResume = entry.canResume()
                && TransferStatusHelper.canResumeOrRetry(entry,
                        transferId -> viewModel.hasPfd(transferId));

        popup.getMenu().findItem(R.id.action_pause_group).setEnabled(isActive);
        popup.getMenu().findItem(R.id.action_resume_group).setEnabled(canResume && !isActive);

        popup.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == R.id.action_pause_group) {
                viewModel.pauseGroup(group);
                Toast.makeText(requireContext(), "已暂停传输", Toast.LENGTH_SHORT).show();
                return true;
            } else if (id == R.id.action_resume_group) {
                viewModel.resumeGroup(group);
                Toast.makeText(requireContext(), "正在重试传输", Toast.LENGTH_SHORT).show();
                return true;
            } else if (id == R.id.action_delete_group) {
                viewModel.deleteGroup(group);
                Toast.makeText(requireContext(),
                        getString(R.string.group_deleted, group.totalEntries),
                        Toast.LENGTH_SHORT).show();
                return true;
            }
            return false;
        });

        popup.show();
    }

    /** 已完成的拉取条目：打开 / 转发 / 保存 / 删除。 */
    private void showPullEntryMenu(SendEntry entry, View anchor) {
        PopupMenu popup = new PopupMenu(requireActivity(), anchor);
        popup.getMenu().add(0, 1, 0, "打开");
        popup.getMenu().add(0, 2, 0, "转发");
        popup.getMenu().add(0, 3, 0, "保存到下载");
        popup.getMenu().add(0, 4, 0, "删除");
        popup.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == 1) {
                App.get().transferManager.openPulledFile(entry);
                return true;
            } else if (id == 2) {
                App.get().transferManager.sharePulledFile(entry);
                return true;
            } else if (id == 3) {
                savePullEntry(entry);
                return true;
            } else if (id == 4) {
                App.get().transferManager.deletePulledFile(entry);
                viewModel.refreshTasks();
                Toast.makeText(requireContext(), "已删除", Toast.LENGTH_SHORT).show();
                return true;
            }
            return false;
        });
        popup.show();
    }

    /** 保存到下载目录，完成后弹 dialog 告知路径。 */
    private void savePullEntry(SendEntry entry) {
        App.get().transferManager.savePulledFile(entry,
                path -> new AlertDialog.Builder(requireContext())
                        .setTitle("已保存")
                        .setMessage("已保存到下载目录：\n" + path)
                        .setPositiveButton(R.string.ok, null)
                        .show(),
                err -> Toast.makeText(requireContext(),
                        "保存失败：" + err, Toast.LENGTH_SHORT).show());
    }
}
