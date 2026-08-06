package github.linsmod.sharetopc.ui;

import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.res.TypedArray;
import android.util.TypedValue;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import github.linsmod.sharetopc.App;
import github.linsmod.sharetopc.R;
import github.linsmod.sharetopc.data.SendEntry;
import github.linsmod.sharetopc.transfer.EtpClient;
import github.linsmod.sharetopc.transfer.StreamingShare;
import github.linsmod.sharetopc.transfer.TransferManager;
import github.linsmod.sharetopc.util.FileUtils;

/**
 * 文件属性 + 操作 统一对话框（浏览/搜索共用）。
 * 属性与全部操作（复制路径 / 拉到手机并打开 / 转发 / 仅拉取 / 流式转发）内联在一个
 * 对话框里，不再弹二次对话框。
 */
public final class EtpFileDialog {

    private EtpFileDialog() {}

    /**
     * 展示文件属性与操作对话框。
     * @param deviceId PC 设备 id（拉取/流式用）
     * @param host     接收端主机
     * @param port     接收端端口
     */
    public static void show(Context ctx, EtpClient.EtpSearchResult r,
                            String deviceId, String host, int port) {
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);

        // 属性文本（可滚动、可选中复制）
        TextView tv = new TextView(ctx);
        tv.setText(buildBody(ctx, r));
        tv.setTextSize(14);
        tv.setTextColor(getThemeColor(ctx, android.R.attr.textColorPrimary));
        tv.setPadding(dp(ctx, 16), dp(ctx, 12), dp(ctx, 16), dp(ctx, 8));
        tv.setTextIsSelectable(true);
        ScrollView scroll = new ScrollView(ctx);
        scroll.addView(tv);
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 170)));

        final Dialog[] dialogRef = new Dialog[1];

        if (!r.isFolder) {
            root.addView(divider(ctx));
            root.addView(actionRow(ctx, R.string.etp_pull_open, v -> {
                if (dialogRef[0] != null) dialogRef[0].dismiss();
                enqueuePull(ctx, r, deviceId, host, port, SendEntry.POST_PULL_OPEN);
            }));
            root.addView(actionRow(ctx, R.string.etp_pull_share, v -> {
                if (dialogRef[0] != null) dialogRef[0].dismiss();
                enqueuePull(ctx, r, deviceId, host, port, SendEntry.POST_PULL_SHARE);
            }));
            root.addView(actionRow(ctx, R.string.etp_pull_download_only, v -> {
                if (dialogRef[0] != null) dialogRef[0].dismiss();
                enqueuePull(ctx, r, deviceId, host, port, SendEntry.POST_PULL_NONE);
            }));
            root.addView(actionRow(ctx, R.string.etp_pull_stream, v -> {
                if (dialogRef[0] != null) dialogRef[0].dismiss();
                streamingShare(ctx, r, deviceId, host, port);
            }));
        }
        root.addView(divider(ctx));
        root.addView(actionRow(ctx, "复制路径", v -> {
            if (dialogRef[0] != null) dialogRef[0].dismiss();
            copyPath(ctx, r);
        }));

        Dialog dialog = new MaterialAlertDialogBuilder(ctx)
                .setTitle(r.name)
                .setView(root)
                .setNegativeButton("关闭", null)
                .create();
        dialogRef[0] = dialog;
        dialog.show();
    }

    private static String buildBody(Context ctx, EtpClient.EtpSearchResult r) {
        StringBuilder body = new StringBuilder();
        body.append("路径：").append(r.fullPath()).append("\n\n");
        body.append("类型：").append(r.isFolder ? "文件夹" : "文件").append("\n\n");

        if (r.size >= 0) {
            body.append("大小：").append(EtpClient.EtpSearchResult.formatSize(r.size));
            if (r.size >= 1024) {
                body.append("（").append(String.format("%,d 字节", r.size)).append("）");
            }
            body.append("\n\n");
        }
        if (r.dateModified > 0) {
            long millis = EtpClient.EtpSearchResult.filetimeToMillis(r.dateModified);
            body.append("修改时间：")
                    .append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                            .format(new Date(millis)))
                    .append("\n\n");
        }
        if (r.dateCreated > 0) {
            long millis = EtpClient.EtpSearchResult.filetimeToMillis(r.dateCreated);
            body.append("创建时间：")
                    .append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                            .format(new Date(millis)))
                    .append("\n\n");
        }
        if (r.attributes >= 0) {
            body.append("属性：").append(formatFileAttributes(r.attributes));
        }
        return body.toString();
    }

    private static void copyPath(Context ctx, EtpClient.EtpSearchResult r) {
        ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("file_path", r.fullPath()));
        Toast.makeText(ctx, ctx.getString(R.string.etp_copied, r.fullPath()),
                Toast.LENGTH_SHORT).show();
    }

    private static void enqueuePull(Context ctx, EtpClient.EtpSearchResult r,
                                    String deviceId, String host, int port, int postPullAction) {
        if (host == null || port <= 0) {
            Toast.makeText(ctx, R.string.etp_pull_no_target, Toast.LENGTH_SHORT).show();
            return;
        }
        TransferManager.SendJob job = App.get().transferManager.enqueuePull(
                deviceId != null ? deviceId : host,
                host, port, r.name,
                r.size >= 0 ? r.size : 0,
                FileUtils.guessMimeType(r.name),
                r.fullPath(),
                postPullAction);
        if (job != null) {
            Toast.makeText(ctx, R.string.etp_pull_queued, Toast.LENGTH_SHORT).show();
        }
    }

    private static void streamingShare(Context ctx, EtpClient.EtpSearchResult r,
                                       String deviceId, String host, int port) {
        if (host == null || port <= 0) {
            Toast.makeText(ctx, R.string.etp_pull_no_target, Toast.LENGTH_SHORT).show();
            return;
        }
        StreamingShare.share(ctx,
                deviceId != null ? deviceId : host,
                host, port, r.name, r.fullPath(),
                r.size >= 0 ? r.size : 0, FileUtils.guessMimeType(r.name));
    }

    /** 一行可点击操作项。 */
    private static TextView actionRow(Context ctx, int textRes, View.OnClickListener listener) {
        return actionRow(ctx, ctx.getString(textRes), listener);
    }

    private static TextView actionRow(Context ctx, CharSequence text, View.OnClickListener listener) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(15);
        tv.setTextColor(getThemeColor(ctx, com.google.android.material.R.attr.colorPrimary));
        tv.setPadding(dp(ctx, 16), dp(ctx, 13), dp(ctx, 16), dp(ctx, 13));
        tv.setBackgroundResource(selectableItemBackground(ctx));
        tv.setClickable(true);
        tv.setFocusable(true);
        tv.setOnClickListener(listener);
        return tv;
    }

    private static View divider(Context ctx) {
        View v = new View(ctx);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 1)));
        v.setBackgroundColor(0x22000000);
        return v;
    }

    private static int selectableItemBackground(Context ctx) {
        TypedArray ta = ctx.obtainStyledAttributes(
                new int[]{android.R.attr.selectableItemBackground});
        int res = ta.getResourceId(0, 0);
        ta.recycle();
        return res;
    }

    private static String formatFileAttributes(int attrs) {
        StringBuilder sb = new StringBuilder();
        if ((attrs & 0x01) != 0) sb.append("只读 ");
        if ((attrs & 0x02) != 0) sb.append("隐藏 ");
        if ((attrs & 0x04) != 0) sb.append("系统 ");
        if ((attrs & 0x10) != 0) sb.append("目录 ");
        if ((attrs & 0x20) != 0) sb.append("存档 ");
        if ((attrs & 0x80) != 0) sb.append("普通 ");
        if ((attrs & 0x100) != 0) sb.append("临时 ");
        if ((attrs & 0x200) != 0) sb.append("稀疏 ");
        if ((attrs & 0x400) != 0) sb.append("重解析点 ");
        if ((attrs & 0x800) != 0) sb.append("压缩 ");
        if ((attrs & 0x1000) != 0) sb.append("脱机 ");
        if ((attrs & 0x2000) != 0) sb.append("未索引 ");
        if ((attrs & 0x4000) != 0) sb.append("加密 ");
        if (sb.length() == 0) sb.append("无");
        return sb.toString().trim();
    }

    private static int dp(Context ctx, int dp) {
        return (int) (dp * ctx.getResources().getDisplayMetrics().density);
    }

    private static int getThemeColor(Context ctx, int attr) {
        TypedValue tv = new TypedValue();
        ctx.getTheme().resolveAttribute(attr, tv, true);
        return ctx.getColor(tv.resourceId);
    }
}
