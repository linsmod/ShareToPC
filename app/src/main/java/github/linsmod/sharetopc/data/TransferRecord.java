package github.linsmod.sharetopc.data;

import java.util.UUID;

/** 传输历史记录。保留足够字段以支持失败重发与暂存后补发。 */
public class TransferRecord {

    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILED = "FAILED";
    /** 暂存：尚未选择目标设备，等待补发 */
    public static final String STATUS_PENDING = "PENDING";

    public String id;
    public long timeMillis;
    public int contentType;      // PendingContent.TYPE_TEXT / TYPE_FILE
    public String name;          // 文件名或文本预览
    public long size;
    public String mime;
    public boolean success;
    public String status;        // SUCCESS / FAILED / PENDING（旧数据可能为空，由 success 推导）
    public String errorMsg;

    // ---- 重发/补发所需信息 ----
    public String textContent;   // TYPE_TEXT 时的完整文本
    public String localPath;     // TYPE_FILE 时的本地路径（可能已被清理）
    public String contentUri;    // TYPE_FILE 时的原始 content:// URI（已申请持久权限，清理后可回退复制）
    public String host;
    public int port;
    public String targetName;
    public String policy;        // CleanupPolicy name

    public static TransferRecord from(PendingContent c, String host, int port,
                                      String targetName, String policy,
                                      boolean success, String errorMsg) {
        TransferRecord r = base(c, policy);
        r.host = host;
        r.port = port;
        r.targetName = targetName;
        r.success = success;
        r.status = success ? STATUS_SUCCESS : STATUS_FAILED;
        r.errorMsg = errorMsg;
        return r;
    }

    /** 暂存记录：未选择目标设备，等待在历史中补发。 */
    public static TransferRecord pending(PendingContent c, String policy) {
        TransferRecord r = base(c, policy);
        r.success = false;
        r.status = STATUS_PENDING;
        return r;
    }

    private static TransferRecord base(PendingContent c, String policy) {
        TransferRecord r = new TransferRecord();
        r.id = UUID.randomUUID().toString();
        r.timeMillis = System.currentTimeMillis();
        r.contentType = c.type;
        r.name = c.name;
        r.size = c.size;
        r.mime = c.mime;
        r.textContent = c.text;
        r.localPath = c.localPath;
        r.contentUri = c.contentUri;
        r.policy = policy;
        return r;
    }

    /** 状态读取（兼容旧数据：无 status 字段时按 success 推导）。 */
    public String statusOrLegacy() {
        if (status != null) {
            return status;
        }
        return success ? STATUS_SUCCESS : STATUS_FAILED;
    }

    public boolean isPending() {
        return STATUS_PENDING.equals(statusOrLegacy());
    }

    /** 内容仍可投递：文本永远可以；文件需本地副本仍存在，或有 contentUri 可回退复制。 */
    public boolean canDeliver() {
        if (contentType == PendingContent.TYPE_TEXT) {
            return textContent != null;
        }
        return (localPath != null && new java.io.File(localPath).exists())
                || contentUri != null;
    }

    public PendingContent toPendingContent() {
        if (contentType == PendingContent.TYPE_TEXT) {
            return PendingContent.ofText(textContent);
        }
        return PendingContent.ofFile(localPath, name, size, mime, contentUri);
    }
}
