package github.linsmod.sharetopc.util;

import android.content.Context;

import androidx.annotation.Nullable;

import github.linsmod.sharetopc.R;
import github.linsmod.sharetopc.data.SendEntry;
import github.linsmod.sharetopc.data.TransferRecord;

/**
 * 传输状态公共映射工具：三处传输 Item（设备传输子项 / 传输任务 / 设备任务弹窗）
 * 共用同一套状态→颜色、状态→文案、重试禁用判断的映射逻辑。
 * <p>
 * 内部 6 种状态映射为 4 种用户可见状态，降低心智负担：
 * <ul>
 *   <li>传输中：SENDING / CONNECTING / PENDING</li>
 *   <li>已暂停：PAUSED / CANCELLED</li>
 *   <li>已完成：SUCCESS</li>
 *   <li>失败：FAILED</li>
 * </ul>
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
    /**
     * 判断传输记录是否可以重试/继续。
     * - 文本类型只要有 textContent 就可以
     * - 文件类型需 contentUri/localPath 可用，且 PFD 仍有效
     */
    public static boolean canResumeOrRetry(TransferRecord record, @Nullable PfdChecker pfdChecker) {
        if (record == null) return false;
        if (record.contentType == SendEntry.TYPE_TEXT) {
            return record.textContent != null;
        }
        return pfdChecker != null
                && pfdChecker.hasPfd(record.id)
                && (record.contentUri != null
                    || (record.localPath != null && new java.io.File(record.localPath).exists()));
    }

    /** 状态圆点颜色（使用简化后的用户可见状态）。 */
    public static int dotColor(Context ctx, TransferRecord r) {
        if (r == null) return 0;
        switch (r.status) {
            case TransferRecord.STATUS_IDLE:
                if(r.idleReason==TransferRecord.IDLE_REASON_SUCCESS)
                    return ctx.getColor(android.R.color.holo_green_dark);
                else if(r.idleReason==TransferRecord.IDLE_REASON_CREATED){
                    return ctx.getColor(android.R.color.darker_gray);
                }
                else if(r.idleReason==TransferRecord.IDLE_REASON_IO_ERROR||
                r.idleReason==TransferRecord.IDLE_REASON_NET_ERROR){
                    return ctx.getColor(android.R.color.holo_red_dark);
                }
                else if(r.idleReason==TransferRecord.IDLE_REASON_PAUSED){
                    return ctx.getColor(android.R.color.holo_blue_light);
                }
            case TransferRecord.STATUS_SENDING:
                return ctx.getColor(android.R.color.holo_blue_light);
            default:
                return ctx.getColor(android.R.color.holo_red_dark);
        }
    }

    /** 进度百分比文字颜色：100% 绿色，其余蓝色。 */
    public static int percentColor(Context ctx, int percent) {
        if (percent >= 100) {
            return ctx.getColor(android.R.color.holo_green_dark);
        }
        return ctx.getColor(android.R.color.holo_blue_dark);
    }
}