package github.linsmod.sharetopc.data;

import android.os.ParcelFileDescriptor;

import java.util.UUID;

import github.linsmod.sharetopc.transfer.TransferProtocol;

/** 待发送内容：文本（内存）或文件（content:// URI，实时读流，不落地）。 */
public class SendEntry extends TransferRecord  {

    public static final int TYPE_TEXT = 1;
    public static final int TYPE_FILE = 2;

    public int type;
    /** TYPE_TEXT 时的文本内容 */
    public String text;

    public static SendEntry ofText(String text) {
        SendEntry c = new SendEntry();
        c.id = UUID.randomUUID().toString();
        c.type = TYPE_TEXT;
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
        c.type = TYPE_FILE;
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
        c.type = TYPE_FILE;
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
        return type == TYPE_TEXT;
    }
}
