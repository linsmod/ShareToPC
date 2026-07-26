package github.linsmod.sharetopc.data;

/** 待发送内容：文本或已落地到 inbox 的文件。 */
public class PendingContent {

    public static final int TYPE_TEXT = 1;
    public static final int TYPE_FILE = 2;

    public int type;
    /** TYPE_TEXT 时的文本内容 */
    public String text;
    /** TYPE_FILE 时的本地路径 */
    public String localPath;
    /** 文件名或文本预览名 */
    public String name;
    public long size;
    public String mime;

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
        PendingContent c = new PendingContent();
        c.type = TYPE_FILE;
        c.localPath = localPath;
        c.name = name;
        c.size = size;
        c.mime = mime;
        return c;
    }

    public boolean isText() {
        return type == TYPE_TEXT;
    }
}
