package github.linsmod.sharetopc.ui;

import android.content.Context;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.view.ContextThemeWrapper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.button.MaterialButton;

import github.linsmod.sharetopc.R;

/**
 * 统一的 Material3 TextButton：默认套用 Widget.ShareToPC.TextButton 样式。
 */
public class TextButton extends MaterialButton {
    public TextButton(@NonNull Context context) {
        this(context, null);
    }

    public TextButton(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public TextButton(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(new ContextThemeWrapper(context, R.style.ThemeOverlay_ShareToPC_TextButton),
                attrs, com.google.android.material.R.attr.materialButtonStyle);
    }
}
