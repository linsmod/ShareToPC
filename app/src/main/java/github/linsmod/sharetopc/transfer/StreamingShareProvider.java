package github.linsmod.sharetopc.transfer;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import github.linsmod.sharetopc.App;
import github.linsmod.sharetopc.util.LogX;

/**
 * 流式分享 ContentProvider：把 PC 端文件"边拉边灌"给目标应用（不落盘，只经过管道内存缓冲）。
 * 目标应用通过 content://…/pull/{nonce} 打开，provider 后台从 PC 下载并写入 pipe，
 * 管道缓冲提供背压（目标读多快就拉多快）。URI 需先经 {@link #register} 注册（随机 nonce），
 * 避免外部应用任意触发网络下载。
 *
 * 说明：pipe 只支持顺序读；绝大多数 content 消费方为顺序读。需要随机 seek 的应用（部分播放器）
 * 不适用——PC 端支持 seek，但 pipe 无法回放，暂不做 seek 支持。
 */
public class StreamingShareProvider extends ContentProvider {

    public static final String AUTHORITY_SUFFIX = ".streaming";

    /** 待流式下载的请求（key = 随机 nonce）。 */
    private static final ConcurrentHashMap<String, PullSpec> SPECS = new ConcurrentHashMap<>();

    /** 流式下载参数。 */
    public static class PullSpec {
        public final String deviceId;
        public final String host;
        public final int port;
        public final String name;
        public final String path;
        public final long size;
        public final String mime;

        public PullSpec(String deviceId, String host, int port,
                        String name, String path, long size, String mime) {
            this.deviceId = deviceId;
            this.host = host;
            this.port = port;
            this.name = name;
            this.path = path;
            this.size = size;
            this.mime = mime;
        }
    }

    /** 注册一次流式下载，返回可授予目标应用的 content URI。 */
    public static Uri register(PullSpec spec) {
        String nonce = UUID.randomUUID().toString();
        if (SPECS.size() > 32) {
            SPECS.clear(); // 防泄漏：超出阈值清空旧的
        }
        SPECS.put(nonce, spec);
        return Uri.parse("content://" + App.get().getPackageName()
                + AUTHORITY_SUFFIX + "/pull/" + nonce);
    }

    private static String nonceOf(Uri uri) {
        return uri != null ? uri.getLastPathSegment() : null;
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Nullable
    @Override
    public String getType(@NonNull Uri uri) {
        PullSpec spec = SPECS.get(nonceOf(uri));
        return spec != null && spec.mime != null && !spec.mime.isEmpty()
                ? spec.mime : "application/octet-stream";
    }

    @Nullable
    @Override
    public ParcelFileDescriptor openFile(@NonNull Uri uri, @NonNull String mode)
            throws FileNotFoundException {
        if (!"r".equals(mode) && !"rw".equals(mode)) return null;
        return openPipeReadEnd(uri);
    }

    @Nullable
    @Override
    public AssetFileDescriptor openAssetFile(@NonNull Uri uri, @NonNull String mode)
            throws FileNotFoundException {
        if (!"r".equals(mode) && !"rw".equals(mode)) return null;
        PullSpec spec = SPECS.get(nonceOf(uri));
        ParcelFileDescriptor read = openPipeReadEnd(uri);
        long length = spec != null && spec.size > 0
                ? spec.size : AssetFileDescriptor.UNKNOWN_LENGTH;
        return new AssetFileDescriptor(read, 0, length);
    }

    /** 创建管道并启动后台下载线程，返回读端（写端交给下载线程）。 */
    private ParcelFileDescriptor openPipeReadEnd(Uri uri) throws FileNotFoundException {
        ParcelFileDescriptor[] pipe;
        try {
            pipe = ParcelFileDescriptor.createPipe();
        } catch (IOException e) {
            throw new FileNotFoundException("无法创建流式管道: " + e.getMessage());
        }
        PullSpec spec = SPECS.get(nonceOf(uri));
        if (spec == null) {
            closeQuietly(pipe[0]);
            closeQuietly(pipe[1]);
            throw new FileNotFoundException("未知或已过期的流式分享");
        }
        streamAsync(pipe[1], spec);
        return pipe[0];
    }

    /** 后台线程：连接 PC → PULL_REQUEST → 把 PULL_CHUNK 写入管道（背压控制内存占用）。 */
    private static void streamAsync(ParcelFileDescriptor writeEnd, PullSpec spec) {
        App.get().ioExecutor.execute(() -> {
            try (OutputStream os = new ParcelFileDescriptor.AutoCloseOutputStream(writeEnd);
                 TransferClient client = new TransferClient(spec.deviceId, spec.host, spec.port)) {
                client.connect();
                client.hello(null, true);
                client.pullToStream(spec.deviceId, spec.name, spec.path, spec.size, os);
            } catch (IOException e) {
                // 目标应用提前关闭/网络中断：写端抛出，静默（正常结束路径）
            } catch (Exception e) {
                LogX.e("streaming share failed: " + spec.path, e);
            }
        });
    }

    private static void closeQuietly(ParcelFileDescriptor pfd) {
        if (pfd != null) {
            try {
                pfd.close();
            } catch (IOException ignored) {}
        }
    }

    // ---- 其余方法占位 ----

    /**
     * 返回文件名与真实大小（探测时 PC 端上报），供分享接收方（如 ShareParser）读取。
     * 否则 OpenableColumns.SIZE 读不到，size 会变成 -1/0，导致发送头 size 错误。
     */
    @Nullable
    @Override
    public Cursor query(@NonNull Uri uri, @Nullable String[] projection, @Nullable String selection,
                        @Nullable String[] selectionArgs, @Nullable String sortOrder) {
        PullSpec spec = SPECS.get(nonceOf(uri));
        if (spec == null) return null;
        MatrixCursor cursor = new MatrixCursor(new String[]{
                OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        cursor.addRow(new Object[]{spec.name, spec.size});
        return cursor;
    }

    @Nullable
    @Override
    public Uri insert(@NonNull Uri uri, @Nullable ContentValues values) {
        return null;
    }

    @Override
    public int delete(@NonNull Uri uri, @Nullable String selection, @Nullable String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(@NonNull Uri uri, @Nullable ContentValues values,
                      @Nullable String selection, @Nullable String[] selectionArgs) {
        return 0;
    }
}
