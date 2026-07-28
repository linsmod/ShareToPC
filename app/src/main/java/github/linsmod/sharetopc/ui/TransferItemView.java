package github.linsmod.sharetopc.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
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
import github.linsmod.sharetopc.data.TransferRecord;
import github.linsmod.sharetopc.util.FileUtils;
import github.linsmod.sharetopc.util.TransferStatusHelper;
public class TransferItemView extends LinearLayout {
    static final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
    public static BindData createBindData(Context ctx,TransferRecord record) {
        var data  = new BindData();
        data.id = record.id;

        // 文件名（带 [文本] 前缀）
        String prefix = record.contentType == SendEntry.TYPE_TEXT
                ? "[" + ctx.getString(R.string.type_text) + "] " : "";
        data.name = prefix + (record.name != null ? record.name : "");

        // 状态圆点
        data.statusColor = TransferStatusHelper.dotColor(ctx, record);

        // 元信息：状态 · 大小 · 时间
        StringBuilder meta = new StringBuilder(record.statusLabel());
        if (record.size > 0) {
            meta.append(" · ").append(FileUtils.formatSize(record.size));
        }
        if (record.timeMillis > 0) {
            meta.append(" · ").append(timeFormat.format(new Date(record.timeMillis)));
        }
        data.meta = meta.toString();

        // 百分比 + 进度条
        boolean hasProgress = record.totalBytes > 0
                && (TransferRecord.STATUS_SENDING==(record.getStatus())
                || record.isPaused()
        );
        if (hasProgress) {
            int pct = (int) (record.sentBytes * 100 / record.totalBytes);
            data.showProgressBar = true;
            data.progress = pct;
            data.percent = pct;
        }
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
        /** 百分比数值，-1 表示隐藏。 */
        public int percent = -1;
        /** 进度条进度 0–100。 */
        public boolean showProgressBar = false;
        public int progress = 0;
        /** 多选框。 */
        public boolean showCheckbox = false;
        public boolean checkboxChecked = false;
    }

    private final View vStatus;
    private final CheckBox cbSelect;
    private final TextView tvName;
    private final TextView tvPercent;
    private final ProgressBar progressBar;
    private final TextView tvMeta;

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
        cbSelect = findViewById(R.id.cbTransferSelect);
        tvName = findViewById(R.id.tvTransferName);
        tvPercent = findViewById(R.id.tvTransferPercent);
        progressBar = findViewById(R.id.progressTransfer);
        tvMeta = findViewById(R.id.tvTransferMeta);
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

    public void setPercent(int percent) {
        if (percent < 0) {
            tvPercent.setVisibility(GONE);
            return;
        }
        tvPercent.setVisibility(VISIBLE);
        tvPercent.setText(percent + "%");
        tvPercent.setTextColor(TransferStatusHelper.percentColor(getContext(), percent));
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

    public void setPercentVisible(boolean visible) {
        if (!visible) tvPercent.setVisibility(GONE);
    }

    public void setProgressBarVisible(boolean visible) {
        progressBar.setVisibility(visible ? VISIBLE : GONE);
    }

    public void setCheckboxVisible(boolean visible, boolean checked) {
        cbSelect.setVisibility(visible ? VISIBLE : GONE);
        if (visible) cbSelect.setChecked(checked);
    }

    public boolean isCheckboxChecked() {
        return cbSelect.isChecked();
    }

    // ── 统一绑定入口 ──────────────────────────────

    /** 一次调用完成所有字段绑定。 */
    public void bind(@NonNull TransferRecord record) {
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

        // 百分比
        if (data.percent >= 0) {
            tvPercent.setVisibility(VISIBLE);
            tvPercent.setText(data.percent + "%");
            tvPercent.setTextColor(TransferStatusHelper.percentColor(getContext(), data.percent));
        } else {
            tvPercent.setVisibility(GONE);
        }

        // 进度条
        progressBar.setVisibility(data.showProgressBar ? VISIBLE : GONE);
        if (data.showProgressBar) {
            progressBar.setProgress(Math.max(0, Math.min(100, data.progress)));
        }

        // meta 行
        tvMeta.setVisibility(data.meta != null && !data.meta.isEmpty() ? VISIBLE : GONE);
        tvMeta.setText(data.meta != null ? data.meta : "");

        // 多选框
        cbSelect.setVisibility(data.showCheckbox ? VISIBLE : GONE);
        if (data.showCheckbox) cbSelect.setChecked(data.checkboxChecked);
    }
}
