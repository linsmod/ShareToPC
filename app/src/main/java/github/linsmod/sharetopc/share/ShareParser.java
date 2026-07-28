package github.linsmod.sharetopc.share;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.List;

import github.linsmod.sharetopc.data.SendEntry;
import github.linsmod.sharetopc.util.FileUtils;
import github.linsmod.sharetopc.util.LogX;

/**
 * Intent → List&lt;SendEntry&gt;。
 * 不复制文件到 inbox，直接记录 content:// URI，发送时实时读流。
 * 请在后台线程执行 {@link #parse}。
 */
public final class ShareParser {

    private ShareParser() {
    }

    public static List<SendEntry> parse(Context context, Intent intent) {
        List<SendEntry> result = new ArrayList<>();
        if (intent == null || intent.getAction() == null) {
            return result;
        }
        String action = intent.getAction();
        if (Intent.ACTION_SEND.equals(action)) {
            String text = intent.getStringExtra(Intent.EXTRA_TEXT);
            Uri stream = getParcelable(intent, Intent.EXTRA_STREAM);
            if (stream != null) {
                SendEntry file = resolveUriContent(context, stream,
                        intent.getType());
                result.add(file);
            } else if (!TextUtils.isEmpty(text)) {
                result.add(SendEntry.ofText(text));
            }
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            List<Uri> uris = getParcelableList(intent, Intent.EXTRA_STREAM);
            if (uris != null) {
                for (Uri uri : uris) {
                    SendEntry file = resolveUriContent(context, uri,
                            intent.getType());
                    result.add(file);
                }
            }
        }
        return result;
    }

    /** 查询 content:// URI 的元数据，申请持久读权限，返回 SendEntry（不复制文件）。 */
    private static SendEntry resolveUriContent(Context context, Uri uri, String intentType) {
        ContentResolver resolver = context.getContentResolver();
        String displayName = null;
        long size = -1;

        try (Cursor cursor = resolver.query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (nameIdx >= 0) {
                    displayName = cursor.getString(nameIdx);
                }
                if (sizeIdx >= 0) {
                    size = cursor.getLong(sizeIdx);
                }
            }
        } catch (Exception e) {
            LogX.e("query uri failed: " + uri, e);
        }

        if (TextUtils.isEmpty(displayName)) {
            displayName = "shared_" + System.currentTimeMillis();
        }
        String mime = resolver.getType(uri);
        if (mime == null) {
            mime = intentType != null ? intentType : FileUtils.guessMimeType(displayName);
        }

        return SendEntry.ofFileFromUri(uri.toString(),
                FileUtils.sanitizeFileName(displayName), size, mime);
    }

    /** 为已创建的 SendEntry 打开 PFD（内核文件句柄），支持可靠的 skip/seek 断点续传。 */
    public static ParcelFileDescriptor createPfd(Context context, SendEntry item) {
        if (item.type != SendEntry.TYPE_FILE || item.contentUri == null) return null;
        try {
            Uri uri = Uri.parse(item.contentUri);
            return context.getContentResolver().openFileDescriptor(uri, "r");
        } catch (Exception e) {
            LogX.e("Failed to open PFD for " + item.name, e);
            return null;
        }
    }

    private static Uri getParcelable(Intent intent, String key) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return intent.getParcelableExtra(key, Uri.class);
        }
        //noinspection deprecation
        return intent.getParcelableExtra(key);
    }

    private static ArrayList<Uri> getParcelableList(Intent intent, String key) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return intent.getParcelableArrayListExtra(key, Uri.class);
        }
        //noinspection deprecation
        return intent.getParcelableArrayListExtra(key);
    }
}
