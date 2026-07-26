package github.linsmod.sharetopc.data;

import android.os.ParcelFileDescriptor;

import java.util.UUID;

/** 待发送内容：文本（内存）或文件（content:// URI，实时读流，不落地）。 */
public class PendingContent {

    public static final int TYPE_TEXT = 1;
    public static final int TYPE_FILE = 2;

    public int type;
    /** TYPE_TEXT 时的文本内容 */
    public String text;
    /** TYPE_FILE 时的本地路径（历史兼容，新分享为 null） */
    public String localPath;
    /** TYPE_FILE 时的 content:// URI（含已申请的持久读权限），新分享的主数据源 */
    public String contentUri;
    /** TYPE_FILE 时的内核文件句柄（预打开，支持可靠的 skip/seek 断点续传）。不持久化，进程死亡自动关闭。 */
    public transient ParcelFileDescriptor pfd;
    /** 文件名或文本预览名 */
    public String name;
    public long size;
    public String mime;
    /** 传输实例唯一 ID（多次分享同名文件时用于区分） */
    public String transferId;

    public static PendingContent ofText(String text) {
        PendingContent c = new PendingContent();
        c.type = TYPE_TEXT;
        c.text = text;
        c.name = text.length() > 20 ? text.substring(0, 20) + "…" : text;
        c.size = text.getBytes().length;
        c.mime = "text/plain";
        return c;
    }

    public static PendingContent ofFile(String localPath, String name, long size, String mime) {
        return ofFile(localPath, name, size, mime, null);
    }

    public static PendingContent ofFile(String localPath, String name, long size,
                                        String mime, String contentUri) {
        PendingContent c = new PendingContent();
        c.type = TYPE_FILE;
        c.localPath = localPath;
        c.name = name;
        c.size = size;
        c.mime = mime;
        c.contentUri = contentUri;
        return c;
    }

    /** 直接从 content:// URI 构建（不落地 inbox），发送时实时读流。 */
    public static PendingContent ofFileFromUri(String contentUri, String name, long size, String mime) {
        PendingContent c = new PendingContent();
        c.type = TYPE_FILE;
        c.localPath = null;
        c.contentUri = contentUri;
        c.name = name;
        c.size = size;
        c.mime = mime;
        return c;
    }

    /** 是否有可直接读取的内容流（contentUri 或仍存在的 localPath）。 */
    public boolean hasReadableSource() {
        if (contentUri != null) return true;
        return localPath != null && new java.io.File(localPath).exists();
    }

    public boolean isText() {
        return type == TYPE_TEXT;
    }
}
