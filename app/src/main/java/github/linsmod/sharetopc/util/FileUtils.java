package github.linsmod.sharetopc.util;

import android.content.Context;
import android.webkit.MimeTypeMap;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;

/** 文件工具：流复制、inbox 目录、文件名清洗、MimeType 查询。 */
public final class FileUtils {

    public static final int BUFFER_SIZE = 64 * 1024;

    private FileUtils() {
    }

    /** 应用私有收件箱根目录：files/inbox */
    public static File inboxRoot(Context context) {
        File dir = new File(context.getFilesDir(), "inbox");
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return dir;
    }

    /** 按日期分组的收件箱子目录：files/inbox/yyyyMMdd */
    public static File inboxDateDir(Context context) {
        String day = new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
        File dir = new File(inboxRoot(context), day);
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return dir;
    }

    /** 在今日目录下生成唯一文件：uuid_清洗后的文件名 */
    public static File uniqueInboxFile(Context context, String displayName) {
        String safe = sanitizeFileName(displayName);
        if (safe.isEmpty()) {
            safe = "unnamed";
        }
        return new File(inboxDateDir(context), UUID.randomUUID() + "_" + safe);
    }

    /** 清洗文件名中的非法字符 [\\/:*?"<>|] */
    public static String sanitizeFileName(String name) {
        if (name == null) {
            return "";
        }
        return name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
    }

    public static long copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[BUFFER_SIZE];
        long total = 0;
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
            total += n;
        }
        out.flush();
        return total;
    }

    public static void deleteQuietly(File file) {
        if (file == null) {
            return;
        }
        try {
            if (file.isDirectory()) {
                File[] children = file.listFiles();
                if (children != null) {
                    for (File child : children) {
                        deleteQuietly(child);
                    }
                }
            }
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        } catch (Throwable t) {
            LogX.e("delete failed: " + file, t);
        }
    }

    public static void closeQuietly(Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (IOException ignored) {
            }
        }
    }

    /** 依据扩展名猜测 MimeType。 */
    public static String guessMimeType(String fileName) {
        if (fileName == null) {
            return "application/octet-stream";
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "application/octet-stream";
        }
        String ext = fileName.substring(dot + 1).toLowerCase(Locale.US);
        String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        return mime != null ? mime : "application/octet-stream";
    }

    public static String formatSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        }
        if (bytes < 1024L * 1024 * 1024) {
            return String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024);
        }
        return String.format(Locale.US, "%.2f GB", bytes / 1024.0 / 1024 / 1024);
    }
}
