package github.linsmod.sharetopc.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.Nullable;

import github.linsmod.sharetopc.R;
import github.linsmod.sharetopc.util.TransferStatusHelper;

/**
 * 统一传输项控件，封装 item_transfer_unified 的视图绑定 + 状态映射 + 按钮逻辑。
 * 三处复用：设备传输子项 / 传输历史 / 设备任务弹窗。
 *
 * <pre>
 * 用法：
 *   TransferItemView item = new TransferItemView(context);
 *   BindData data = new BindData();
 *   data.name = "file.txt";
 *   data.status = "SENDING";
 *   data.meta = "传输中 · 192.168.1.1:80 · 1.2MB · 14:30";
 *   data.actionMode = ActionMode.ACTIVE;
 *   item.bind(data);
 *   item.setOnActionListener(listener);
 * </pre>
 */
public class TransferItemView extends LinearLayout {

    /** 按钮区显示模式。 */
    public enum ActionMode {
        /** 全部隐藏（如 SUCCESS）。 */
        NONE,
        /** 暂停 + 取消（传输中）。 */
        ACTIVE,
        /** 显示操作按钮（重试/继续/发送）。 */
        RETRY
    }

    /** 操作回调。 */
    public interface OnActionListener {
        void onPause();
        void onCancel();
        void onResume();
    }

    /** 统一绑定数据，三处 Adapter 共用同一份字段。 */
    public static class BindData {
        /** 文件名（可含前缀如 "[文本] "）。 */
        public String name = "";
        /** 文件名透明度（1.0 正常，0.4 文件已删除灰化）。 */
        public float nameAlpha = 1.0f;
        /** 传输状态字符串："SENDING" / "SUCCESS" / "FAILED" / "CANCELLED"（映射圆点颜色）。 */
        public String status = "";
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
        /** 按钮区。 */
        public ActionMode actionMode = ActionMode.NONE;
        @Nullable public String actionLabel;
        public boolean actionEnabled = false;
    }

    private final View vStatus;
    private final CheckBox cbSelect;
    private final TextView tvName;
    private final TextView tvPercent;
    private final ProgressBar progressBar;
    private final TextView tvMeta;
    private final ImageButton btnPause;
    private final ImageButton btnCancel;
    private final Button btnResume;

    @Nullable
    private OnActionListener actionListener;

    public TransferItemView(Context context) {
        this(context, null);
    }

    public TransferItemView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public TransferItemView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setOrientation(HORIZONTAL);
        // 代码创建时默认撑满宽度，避免在某些容器中只能显示 wrap_content 宽度
        setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        LayoutInflater.from(context).inflate(R.layout.item_transfer_unified, this, true);

        vStatus = findViewById(R.id.vTransferStatus);
        cbSelect = findViewById(R.id.cbTransferSelect);
        tvName = findViewById(R.id.tvTransferName);
        tvPercent = findViewById(R.id.tvTransferPercent);
        progressBar = findViewById(R.id.progressTransfer);
        tvMeta = findViewById(R.id.tvTransferMeta);
        btnPause = findViewById(R.id.btnTransferPause);
        btnCancel = findViewById(R.id.btnTransferCancel);
        btnResume = findViewById(R.id.btnTransferResume);
    }

    // ── 数据设置 ──────────────────────────────────────────

    public void setName(String name) {
        tvName.setText(name);
    }

    /** 设置名称透明度（如文件已删除则灰化）。 */
    public void setNameAlpha(float alpha) {
        tvName.setAlpha(alpha);
    }

    /** 设置传输状态（自动映射圆点颜色 + 状态文案）。 */
    public void setTransferStatus(String status) {
        Context ctx = getContext();
        vStatus.setBackgroundColor(TransferStatusHelper.dotColor(ctx, status));
    }

    /** 设置状态文案中使用的状态文字（用于 meta 行拼接）。 */
    public static String statusLabel(Context ctx, String status) {
        return TransferStatusHelper.statusText(ctx, status);
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

    // ── 元素可见性 ────────────────────────────────────────

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

    // ── 统一绑定入口 ──────────────────────────────────────

    /** 一次调用完成所有字段绑定（除 setOnActionListener / setOnClickListener 等回调）。 */
    public void bind(@Nullable BindData data) {
        if (data == null) return;

        // 名称
        tvName.setText(data.name != null ? data.name : "");
        tvName.setAlpha(data.nameAlpha);

        // 状态圆点
        if (data.status != null && !data.status.isEmpty()) {
            vStatus.setVisibility(VISIBLE);
            vStatus.setBackgroundColor(TransferStatusHelper.dotColor(getContext(), data.status));
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

        // 按钮区
        setActionMode(data.actionMode, data.actionLabel, data.actionEnabled);
    }

    // ── 按钮区 ────────────────────────────────────────────

    /** 设置操作回调。 */
    public void setOnActionListener(@Nullable OnActionListener listener) {
        this.actionListener = listener;
    }

    /**
     * 设置按钮显示模式。
     *
     * @param mode      NONE / ACTIVE / RETRY
     * @param label     RETRY 模式下按钮文案（如"重试"/"继续"/"发送"）
     * @param enabled   RETRY 模式下按钮是否可点击
     */
    public void setActionMode(ActionMode mode, @Nullable String label, boolean enabled) {
        switch (mode) {
            case NONE:
                btnPause.setVisibility(GONE);
                btnPause.setOnClickListener(null);
                btnCancel.setVisibility(GONE);
                btnCancel.setOnClickListener(null);
                btnResume.setVisibility(GONE);
                btnResume.setOnClickListener(null);
                break;

            case ACTIVE:
                btnResume.setVisibility(GONE);
                btnResume.setOnClickListener(null);
                btnPause.setVisibility(VISIBLE);
                btnPause.setOnClickListener(v -> {
                    if (actionListener != null) actionListener.onPause();
                });
                btnCancel.setVisibility(VISIBLE);
                btnCancel.setOnClickListener(v -> {
                    if (actionListener != null) actionListener.onCancel();
                });
                break;

            case RETRY:
                btnPause.setVisibility(GONE);
                btnPause.setOnClickListener(null);
                btnCancel.setVisibility(GONE);
                btnCancel.setOnClickListener(null);
                btnResume.setVisibility(VISIBLE);
                btnResume.setText(label != null ? label : getContext().getString(R.string.retry));
                btnResume.setEnabled(enabled);
                btnResume.setOnClickListener(v -> {
                    if (actionListener != null) actionListener.onResume();
                });
                break;
        }
    }
}
