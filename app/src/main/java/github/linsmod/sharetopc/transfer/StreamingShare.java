package github.linsmod.sharetopc.transfer;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import github.linsmod.sharetopc.App;
import github.linsmod.sharetopc.util.LogX;

/**
 * 流式转发：不落盘，先探测 PC 端文件大小/MIME，注册流式 URI 后弹分享面板，
 * 目标应用读取时由 {@link StreamingShareProvider} 边拉边灌。
 */
public final class StreamingShare {

    private StreamingShare() {}

    /**
     * 流式转发到其他应用。
     * @param ctx        用于弹分享面板的上下文（Activity）
     */
    public static void share(Context ctx, String deviceId, String host, int port,
                             String name, String path, long estSize, String mime) {
        App.get().ioExecutor.execute(() -> {
            try (TransferClient client = new TransferClient(deviceId, host, port)) {
                client.connect();
                client.hello(null, true);
                TransferClient.PullProbe probe = client.probePull(deviceId, name, path, estSize);
                if (!probe.ok) {
                    App.get().mainHandler.post(() -> Toast.makeText(ctx,
                            "流式转发被拒绝: " + probe.msg, Toast.LENGTH_SHORT).show());
                    return;
                }
                final String finalMime = probe.mime != null && !probe.mime.isEmpty()
                        ? probe.mime : mime;
                final Uri uri = StreamingShareProvider.register(new StreamingShareProvider.PullSpec(
                        deviceId, host, port, name, path, probe.size, finalMime));
                App.get().mainHandler.post(() -> fireShare(ctx, uri, name, finalMime));
            } catch (Exception e) {
                LogX.e("streaming share failed", e);
                App.get().mainHandler.post(() -> Toast.makeText(ctx,
                        "流式转发失败: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        });
    }

    private static void fireShare(Context ctx, Uri uri, String name, String mime) {
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType(mime != null ? mime : "application/octet-stream");
        share.putExtra(Intent.EXTRA_STREAM, uri);
        share.putExtra(Intent.EXTRA_TITLE, name);
        share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        Intent chooser = Intent.createChooser(share, "流式转发");
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(chooser);
    }
}
