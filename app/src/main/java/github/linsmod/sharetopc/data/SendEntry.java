package github.linsmod.sharetopc.data;

import java.util.Objects;
import java.util.UUID;

/** 待发送内容：文本（内存）或文件（content:// URI，实时读流，不落地）。 */
public class SendEntry {
    public static final int STATUS_IDLE = 0;
    public static final int STATUS_SENDING = 1;
    public static final int IDLE_REASON_CREATED=0;
    public static final int IDLE_REASON_OUTDATE=1; // set on app restart for none success
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
    public long updateMillis;

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
        return Objects.equals(status, SendEntry.STATUS_IDLE) && idleReason==SendEntry.IDLE_REASON_PAUSED;
    }

    public boolean canRequestPause() {
        return this.pendingAction==0 && Objects.equals(status, SendEntry.STATUS_SENDING);
    }

    //暂停状态或网络错误时允许重试
    public boolean canResume() {
        return this.pendingAction==0 &&  status==SendEntry.STATUS_IDLE &&
                (
                        idleReason==SendEntry.IDLE_REASON_PAUSED || idleReason==IDLE_REASON_NET_ERROR || idleReason==IDLE_REASON_CREATED);
    }

    public String deviceAddress() {
        return host + ":" + port;
    }
    public static final int TYPE_TEXT = 1;
    public static final int TYPE_FILE = 2;
    /** TYPE_TEXT 时的文本内容 */
    public String text;

    public static SendEntry ofText(String text) {
        SendEntry c = new SendEntry();
        c.id = UUID.randomUUID().toString();
        c.contentType = TYPE_TEXT;
        c.text = text;
        c.name = text.length() > 20 ? text.substring(0, 20) + "…" : text;
        c.size = text.getBytes().length;
        c.mime = "text/plain";
        c.timeMillis = System.currentTimeMillis();
        return c;
    }

    public static SendEntry ofFile(String localPath, String name, long size, String mime) {
        return ofFile(localPath, name, size, mime, null);
    }

    public static SendEntry ofFile(String localPath, String name, long size,
                                   String mime, String contentUri) {
        SendEntry c = new SendEntry();
        c.id = UUID.randomUUID().toString();
        c.contentType = TYPE_FILE;
        c.localPath = localPath;
        c.name = name;
        c.size = size;
        c.mime = mime;
        c.contentUri = contentUri;
        c.timeMillis = System.currentTimeMillis();
        return c;
    }

    /** 直接从 content:// URI 构建（不落地 inbox），发送时实时读流。 */
    public static SendEntry ofFileFromUri(String contentUri, String name, long size, String mime) {
        SendEntry c = new SendEntry();
        c.id = UUID.randomUUID().toString();
        c.contentType = TYPE_FILE;
        c.localPath = null;
        c.contentUri = contentUri;
        c.name = name;
        c.size = size;
        c.mime = mime;
        c.timeMillis = System.currentTimeMillis();
        return c;
    }

    /** 是否有可直接读取的内容流（contentUri 或仍存在的 localPath）。 */
    public boolean hasReadableSource() {
        if (contentUri != null) return true;
        return localPath != null && new java.io.File(localPath).exists();
    }

    public boolean isText() {
        return contentType == TYPE_TEXT;
    }

    public boolean isSending() {
        return status== SendEntry.STATUS_SENDING;
    }
}
