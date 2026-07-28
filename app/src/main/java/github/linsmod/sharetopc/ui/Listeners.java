package github.linsmod.sharetopc.ui;

import android.view.View;

import github.linsmod.sharetopc.data.TransferRecord;

/** 统一的任务操作回调，供 TaskAdapter、DeviceQueueDialog 共用。 */
public final class Listeners {
    private Listeners() {}

    public interface TaskActionListener {
        /** 单条目重试/继续 */
        void onRetryRequested(TransferRecord record);
        /** 单条目暂停 */
        void onPauseRequested(TransferRecord record);
        /** 分组长按 → 弹出上下文菜单 */
        void onGroupMenuRequested(TaskGroup group, View anchor, float touchX);
        /** 暂停整个分组 */
        default void onGroupPauseRequested(TaskGroup group) {}
        /** 继续整个分组 */
        default void onGroupResumeRequested(TaskGroup group) {}
        /** 删除整个分组 */
        default void onGroupDeleteRequested(TaskGroup group) {}
    }
}
