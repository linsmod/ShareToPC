package github.linsmod.sharetopc.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import github.linsmod.sharetopc.R;
import github.linsmod.sharetopc.data.SendEntry;
import github.linsmod.sharetopc.util.FileUtils;
import github.linsmod.sharetopc.util.TransferStatusHelper;
public class TransferItemView extends LinearLayout {
    static final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
    public static BindData createBindData(Context ctx,SendEntry record) {
        var data  = new BindData();
        data.id = record.id;

        // 文件名（带 [文本] 前缀）
        String prefix = record.contentType == SendEntry.TYPE_TEXT
                ? "[" + ctx.getString(R.string.type_text) + "] " : "";
        data.name = prefix + (record.name != null ? record.name : "");

        // 状态圆点
        data.statusColor = TransferStatusHelper.dotColor(ctx, record);

        // 文件大小 —— 第一行尾部
        if (record.size > 0) {
            data.sizeText = FileUtils.formatSize(record.size);
        }

        // 第二行 meta: "状态 · percent% · 时间"
        StringBuilder meta = new StringBuilder(record.statusLabel());
        if (record.totalBytes > 0
                && (SendEntry.STATUS_SENDING == record.getStatus()
                || record.isPaused())) {
            meta.append(" · ").append(record.percent()).append("%");
        }
        if (record.timeMillis > 0) {
            meta.append(" · ").append(timeFormat.format(new Date(record.timeMillis)));
        }
        data.meta = meta.toString();

        // 百分比 + 进度条
        boolean hasProgress = record.totalBytes > 0
                && (SendEntry.STATUS_SENDING==(record.getStatus())
                || record.isPaused()
        );
        if (hasProgress) {
            int pct = (int) (record.sentBytes * 100 / record.totalBytes);
            data.showProgressBar = true;
            data.progress = pct;
        }

        // 发送完成绿色勾
        data.showCheckMark = record.isSuccess();
        // 过期显示灰色点
        data.showOutdateDot = record.status == SendEntry.STATUS_IDLE
                && record.idleReason == SendEntry.IDLE_REASON_OUTDATE;
        // 其他出错显示红色点
        data.showErrorDot = record.status == SendEntry.STATUS_IDLE
                && record.idleReason != SendEntry.IDLE_REASON_SUCCESS
                && record.idleReason != SendEntry.IDLE_REASON_PAUSED
                && record.idleReason != SendEntry.IDLE_REASON_CREATED
                && record.idleReason != SendEntry.IDLE_REASON_OUTDATE;

        return data;
    }

    /** 统一绑定数据，三处 Adapter 共用同一份字段。 */
    public static class BindData {
        /** 记录 id（切换回调时传递）。 */
        @Nullable public String id;
        /** 文件名（可含前缀如 "[文本] "）。 */
        public String name = "";
        /** 文件名透明度（1.0 正常，0.4 文件已删除灰化）。 */
        public float nameAlpha = 1.0f;
        public int statusColor;
        /** meta 行完整文案（Adapter 自行拼接后传入）。 */
        public String meta = "";
        /** 文件尺寸文案，显示在第一行尾部，如 "1.2 MB"。null 表示隐藏。 */
        @Nullable public String sizeText;
        /** 进度条进度 0–100。 */
        public boolean showProgressBar = false;
        public int progress = 0;
        /** 发送完成后显示绿色勾。 */
        public boolean showCheckMark = false;
        /** 传输出错显示红色圆点。 */
        public boolean showErrorDot = false;
        /** 过期显示灰色圆点。 */
        public boolean showOutdateDot = false;
    }

    private final View vStatus;
    private final TextView tvName;
    private final TextView tvSize;
    private final ProgressBar progressBar;
    private final View layoutMetaRow;
    private final TextView tvMeta;
    private final TextView tvCheckMark;
    private final View vErrorDot;
    private final View vOutdateDot;

    public TransferItemView(Context context) {
        this(context, null);
    }

    public TransferItemView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public TransferItemView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setOrientation(HORIZONTAL);
        setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        setClickable(true);

        LayoutInflater.from(context).inflate(R.layout.item_transfer_unified, this, true);

        vStatus = findViewById(R.id.vTransferStatus);
        tvName = findViewById(R.id.tvTransferName);
        tvSize = findViewById(R.id.tvTransferSize);
        progressBar = findViewById(R.id.progressTransfer);
        layoutMetaRow = findViewById(R.id.layoutMetaRow);
        tvMeta = findViewById(R.id.tvTransferMeta);
        tvCheckMark = findViewById(R.id.tvCheckMark);
        vErrorDot = findViewById(R.id.vErrorDot);
        vOutdateDot = findViewById(R.id.vOutdateDot);
    }

    // ── 数据设置 ──────────────────────────────────

    public void setName(String name) {
        tvName.setText(name);
    }

    /** 设置名称透明度（如文件已删除则灰化）。 */
    public void setNameAlpha(float alpha) {
        tvName.setAlpha(alpha);
    }

    public void setMeta(String meta) {
        tvMeta.setText(meta);
    }

    public void setProgress(int percent) {
        progressBar.setProgress(Math.max(0, Math.min(100, percent)));
    }

    public void setStatusColor(int color) {
        vStatus.setBackgroundColor(color);
    }

    // ── 元素可见性 ────────────────────────────────

    public void setStatusDotVisible(boolean visible) {
        vStatus.setVisibility(visible ? VISIBLE : GONE);
    }

    public void setMetaVisible(boolean visible) {
        tvMeta.setVisibility(visible ? VISIBLE : GONE);
    }

    public void setProgressBarVisible(boolean visible) {
        progressBar.setVisibility(visible ? VISIBLE : GONE);
    }

    // ── 统一绑定入口 ──────────────────────────────

    /** 一次调用完成所有字段绑定。 */
    public void bind(@NonNull SendEntry record) {
        TransferItemView.BindData data =  TransferItemView.createBindData(getContext(),record);
        if (data == null) return;

        // 名称
        tvName.setText(data.name != null ? data.name : "");
        tvName.setAlpha(data.nameAlpha);

        // 状态圆点
        if (data.statusColor>0) {
            vStatus.setVisibility(VISIBLE);
            vStatus.setBackgroundColor(data.statusColor);
        } else {
            vStatus.setVisibility(GONE);
        }

        // 文件尺寸 —— 第一行尾部
        if (data.sizeText != null) {
            tvSize.setVisibility(VISIBLE);
            tvSize.setText(data.sizeText);
        } else {
            tvSize.setVisibility(GONE);
        }

        // 进度条
        progressBar.setVisibility(data.showProgressBar ? VISIBLE : GONE);
        if (data.showProgressBar) {
            progressBar.setProgress(Math.max(0, Math.min(100, data.progress)));
        }

        // meta 行
        boolean hasMeta = data.meta != null && !data.meta.isEmpty();
        layoutMetaRow.setVisibility(hasMeta ? VISIBLE : GONE);
        if (hasMeta) {
            tvMeta.setText(data.meta);
        }
        tvCheckMark.setVisibility(data.showCheckMark ? VISIBLE : GONE);
        vErrorDot.setVisibility(data.showErrorDot ? VISIBLE : GONE);
        // disabled in this version.
//        vOutdateDot.setVisibility(data.showOutdateDot ? VISIBLE : GONE);
    }
}
