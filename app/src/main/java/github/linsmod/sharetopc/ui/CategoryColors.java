package github.linsmod.sharetopc.ui;

import android.content.Context;
import android.util.TypedValue;

import java.util.Locale;

import github.linsmod.sharetopc.R;

/** 固定分类配色：文件列表分类标题 / 位置标签按关键词取色，未知分类返回 0。 */
final class CategoryColors {

    private CategoryColors() {
    }

    /** 分类名/位置标签 → 颜色；未识别返回 0（由调用方决定默认色）。 */
    static int color(Context context, String label) {
        if (label == null) return 0;
        String s = label.toLowerCase(Locale.getDefault());
        if (s.contains("图片") || s.contains("image") || s.contains("photo")
                || s.contains("png") || s.contains("jpg") || s.contains("jpeg")) {
            return context.getColor(R.color.category_image);
        }
        if (s.contains("视频") || s.contains("电影") || s.contains("video")) {
            return context.getColor(R.color.category_video);
        }
        if (s.contains("音频") || s.contains("音乐") || s.contains("audio") || s.contains("music")) {
            return context.getColor(R.color.category_audio);
        }
        if (s.contains("文档") || s.contains("doc") || s.contains("word") || s.contains("excel")
                || s.contains("ppt") || s.contains("pdf") || s.contains("text") || s.contains("txt")
                || s.contains("思维导图") || s.contains("xmind") || s.contains("psd")) {
            return context.getColor(R.color.category_document);
        }
        if (s.contains("压缩") || s.contains("zip") || s.contains("rar")
                || s.contains("7z") || s.contains("tar")) {
            return context.getColor(R.color.category_archive);
        }
        if (s.contains("程序") || s.contains("安装") || s.contains("exe")
                || s.contains("msi") || s.contains("apk")) {
            return context.getColor(R.color.category_app);
        }
        if (s.contains("桌面") || s.contains("desktop")) {
            return context.getColor(R.color.category_desktop);
        }
        if (s.contains("下载") || s.contains("download")) {
            return context.getColor(R.color.category_download);
        }
        return 0;
    }

    /** 主题 colorPrimary（分类标题默认色）。 */
    static int headerDefault(Context context) {
        TypedValue out = new TypedValue();
        context.getTheme().resolveAttribute(androidx.appcompat.R.attr.colorPrimary, out, true);
        return out.data;
    }

    /** 位置标签背景：分类色 25% 透明度圆角块（前景仍用主题默认色，保证双主题可读）。 */
    static android.graphics.drawable.GradientDrawable tagBackground(Context context, int color) {
        android.graphics.drawable.GradientDrawable d =
                new android.graphics.drawable.GradientDrawable();
        d.setColor((color & 0x00FFFFFF) | 0x40000000);
        d.setCornerRadius(8 * context.getResources().getDisplayMetrics().density);
        return d;
    }
}
