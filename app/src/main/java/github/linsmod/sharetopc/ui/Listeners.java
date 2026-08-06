package github.linsmod.sharetopc.ui;

import android.view.View;

import github.linsmod.sharetopc.data.SendEntry;

/** 统一的任务操作回调，供 TaskAdapter 和 TaskFragment 共用。 */
public final class Listeners {
    private Listeners() {}

    public interface TaskActionListener {
        /** 分组长按 → 弹出上下文菜单 */
        void onGroupMenuRequested(TaskGroup group, View anchor, float touchX);
        /** 已完成的下载条目长按 → 弹出打开/转发菜单 */
        default void onPullEntryMenuRequested(SendEntry entry, View anchor) {}
        /** 暂停整个分组 */
        default void onGroupPauseRequested(TaskGroup group) {}
        /** 继续整个分组 */
        default void onGroupResumeRequested(TaskGroup group) {}
        /** 删除整个分组 */
        default void onGroupDeleteRequested(TaskGroup group) {}
    }
}
