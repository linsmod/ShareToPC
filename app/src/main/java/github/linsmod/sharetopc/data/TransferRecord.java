package github.linsmod.sharetopc.data;

import java.util.Objects;

import github.linsmod.sharetopc.transfer.TransferProtocol;

/** 传输历史记录。保留足够字段以支持失败重发。 */
public class TransferRecord{
    public static final int STATUS_IDLE = 0;
    public static final int STATUS_SENDING = 1;
    public static final int IDLE_REASON_CREATED=0;
    public static final int IDLE_REASON_OUTDATE=1; // app restart
    public static final int IDLE_REASON_PAUSED=2;
    public static final int IDLE_REASON_NET_ERROR=3;
    public static final int IDLE_REASON_IO_ERROR=4;
    public static final int IDLE_REASON_SUCCESS =6;

    public static final int PENDING_ACTION_NONE=0;
    public static final int PENDING_ACTION_END_PAUSE=1;
    public static final int PENDING_ACTION_END_RESUME=2;
    public String id;
    public int idleReason;
    public int pendingAction;
    public String jobId;
    public long timeMillis;
    public int contentType;      // SendEntry.TYPE_TEXT / TYPE_FILE
    public String name;          // 文件名或文本预览
    public long size;
    public String mime;
    public int status;        // SUCCESS / FAILED / PENDING（旧数据可能为空，由 success 推导）
    public long sentBytes;       // 已发送字节，启动恢复时 0→CANCELLED，>0→FAILED（为断点续传预留）
    public long totalBytes;      // 总字节数
    public String errorMsg;

    // ---- 重发/补发所需信息 ----
    public String textContent;   // TYPE_TEXT 时的完整文本
    public String localPath;     // TYPE_FILE 时的本地路径（可能已被清理）
    public String contentUri;    // TYPE_FILE 时的原始 content:// URI（已申请持久权限，清理后可回退复制）
    public String host;
    public int port;
    public String targetName;
    public String policy;        // CleanupPolicy name

    /** 状态读取（兼容旧数据：无 status 字段时按 success 推导）。 */
    public int getStatus() {
        return status;
    }
    public String statusLabel(){
        if (status==STATUS_SENDING){
                return "发送中";
        }
        else switch (idleReason){
            case IDLE_REASON_CREATED:
                return "准备发送";
            case IDLE_REASON_OUTDATE:
                return "已过期";
            case IDLE_REASON_IO_ERROR:
                return "读取出错";
            case IDLE_REASON_NET_ERROR:
                return "网络错误";
            case IDLE_REASON_PAUSED:
                return "已暂停";
            case IDLE_REASON_SUCCESS:
                return "发送完成";
        }
        return "未知状态";
    }
    public int percent(){
        return totalBytes>0? (int)(sentBytes * 100 / totalBytes):0;
    }

    public SendEntry createSendEntry() {
        SendEntry c;
        if (contentType == SendEntry.TYPE_TEXT) {
            c = SendEntry.ofText(textContent);
        } else if (contentUri != null) {
            // 优先用 contentUri（新路径 / 回退），其次用 localPath（历史兼容）
            c = SendEntry.ofFileFromUri(contentUri, name, size, mime);
        } else {
            c = SendEntry.ofFile(localPath, name, size, mime, null);
        }
        // 保留原 transferId，确保重试时 PC 侧能匹配到同一个 .part 文件
        if (id != null) {
            c.id = id;
        }
        return c;
    }
    public boolean isSuccess(){
        return this.status == STATUS_IDLE && this.idleReason == IDLE_REASON_SUCCESS;
    }
    public void setError(String error){
        this.errorMsg = error;
    }

    public boolean isPaused() {
        return Objects.equals(status, TransferRecord.STATUS_IDLE) && idleReason==TransferRecord.IDLE_REASON_PAUSED;
    }

    public boolean canRequestPause() {
        return this.pendingAction==0 && Objects.equals(status, TransferRecord.STATUS_SENDING);
    }

    //暂停状态或网络错误时允许重试
    public boolean canResume() {
        return this.pendingAction==0 && (
                idleReason==TransferRecord.IDLE_REASON_PAUSED || idleReason==IDLE_REASON_NET_ERROR || idleReason==IDLE_REASON_CREATED);
    }

    public String deviceAddress() {
        return host + ":" + port;
    }
}
