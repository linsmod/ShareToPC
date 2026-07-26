package github.linsmod.sharetopc.share;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.OpenableColumns;
import android.text.TextUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import github.linsmod.sharetopc.data.PendingContent;
import github.linsmod.sharetopc.util.FileUtils;
import github.linsmod.sharetopc.util.LogX;

/**
 * Intent → List&lt;PendingContent&gt;。
 * 注意：URI 读取授权仅在进程存活期有效，必须在 Activity 前台时调用本类完成复制。
 * 请在后台线程执行 {@link #parse}。
 */
public final class ShareParser {

    private ShareParser() {
    }

    public static List<PendingContent> parse(Context context, Intent intent) {
        List<PendingContent> result = new ArrayList<>();
        if (intent == null || intent.getAction() == null) {
            return result;
        }
        String action = intent.getAction();
        if (Intent.ACTION_SEND.equals(action)) {
            String text = intent.getStringExtra(Intent.EXTRA_TEXT);
            Uri stream = getParcelable(intent, Intent.EXTRA_STREAM);
            if (stream != null) {
                PendingContent file = copyUriToInbox(context, stream,
                        intent.getType());
                if (file != null) {
                    result.add(file);
                }
            } else if (!TextUtils.isEmpty(text)) {
                result.add(PendingContent.ofText(text));
            }
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            List<Uri> uris = getParcelableList(intent, Intent.EXTRA_STREAM);
            if (uris != null) {
                for (Uri uri : uris) {
                    PendingContent file = copyUriToInbox(context, uri,
                            intent.getType());
                    if (file != null) {
                        result.add(file);
                    }
                }
            }
        }
        return result;
    }

    /** 复制 content:// URI 到 inbox，生成 PendingContent。失败返回 null。 */
    private static PendingContent copyUriToInbox(Context context, Uri uri, String intentType) {
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

        File target = FileUtils.uniqueInboxFile(context, displayName);
        try (InputStream in = resolver.openInputStream(uri);
             FileOutputStream fos = new FileOutputStream(target)) {
            if (in == null) {
                return null;
            }
            long copied = FileUtils.copy(in, fos);
            return PendingContent.ofFile(target.getAbsolutePath(),
                    FileUtils.sanitizeFileName(displayName), copied, mime);
        } catch (Exception e) {
            LogX.e("copy to inbox failed: " + uri, e);
            FileUtils.deleteQuietly(target);
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
