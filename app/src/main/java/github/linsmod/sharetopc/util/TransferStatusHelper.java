package github.linsmod.sharetopc.util;

import android.content.Context;

import androidx.annotation.Nullable;

import github.linsmod.sharetopc.R;
import github.linsmod.sharetopc.data.PendingContent;
import github.linsmod.sharetopc.data.TransferRecord;

/**
 * 传输状态公共映射工具：三处传输 Item（设备传输子项 / 传输历史 / 设备任务弹窗）
 * 共用同一套 状态→颜色、状态→文案、重试禁用判断 的映射逻辑。
 */
public class TransferStatusHelper {

    /** PFD 可用性查询（断点续传是否可能）。 */
    public interface PfdChecker {
        boolean hasPfd(String transferId);
    }

    /**
     * 判断传输记录是否可以重试/继续。
     * - 文本类型只要有 textContent 就可以
     * - 文件类型需 contentUri/localPath 存在，且 PFD 仍有效
     */
    public static boolean canResumeOrRetry(TransferRecord record, @Nullable PfdChecker pfdChecker) {
        if (record == null || !record.canDeliver()) return false;
        if (record.contentType == PendingContent.TYPE_TEXT) return true;
        return pfdChecker == null || pfdChecker.hasPfd(record.transferId);
    }

    /** 通用状态常量（兼容 TransferRecord 与 DeviceTaskAdapter 的 int 状态）。 */
    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_CANCELLED = "CANCELLED";
    public static final String STATUS_SENDING = "SENDING";
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_PAUSED = "PAUSED";

    /** 状态圆点颜色。 */
    public static int dotColor(Context ctx, String status) {
        if (status == null) return ctx.getColor(android.R.color.darker_gray);
        switch (status) {
            case STATUS_SUCCESS:
                return ctx.getColor(android.R.color.holo_green_dark);
            case STATUS_SENDING:
                return ctx.getColor(android.R.color.holo_blue_light);
            case STATUS_PENDING:
                return ctx.getColor(android.R.color.holo_orange_light);
            case STATUS_CANCELLED:
            case STATUS_PAUSED:
                return ctx.getColor(android.R.color.darker_gray);
            case STATUS_FAILED:
            default:
                return ctx.getColor(android.R.color.holo_red_dark);
        }
    }

    /** 状态文案。 */
    public static String statusText(Context ctx, String status) {
        if (status == null) return "";
        switch (status) {
            case STATUS_SUCCESS:
                return ctx.getString(R.string.result_ok);
            case STATUS_SENDING:
                return ctx.getString(R.string.result_sending);
            case STATUS_PENDING:
                return ctx.getString(R.string.result_pending);
            case STATUS_CANCELLED:
                return ctx.getString(R.string.result_cancelled);
            case STATUS_PAUSED:
                return ctx.getString(R.string.state_paused);
            case STATUS_FAILED:
            default:
                return ctx.getString(R.string.result_failed);
        }
    }

    /** 将 DeviceTaskAdapter 的 int 状态转为通用字符串状态。 */
    public static String fromTaskStatus(int taskStatus) {
        switch (taskStatus) {
            case 1: return STATUS_SENDING;   // STATUS_TRANSFERRING
            case 2: return STATUS_SUCCESS;
            case 3: return STATUS_FAILED;
            case 4: return STATUS_CANCELLED;
            default: return STATUS_FAILED;
        }
    }

    /** 将 TransferRecord 的状态转为通用字符串状态。 */
    public static String fromRecord(TransferRecord record) {
        if (record == null) return STATUS_FAILED;
        return record.statusOrLegacy();
    }

    /** 进度百分比文字颜色：100% 绿色，其余蓝色。 */
    public static int percentColor(Context ctx, int percent) {
        if (percent >= 100) {
            return ctx.getColor(android.R.color.holo_green_dark);
        }
        return ctx.getColor(android.R.color.holo_blue_dark);
    }
}
