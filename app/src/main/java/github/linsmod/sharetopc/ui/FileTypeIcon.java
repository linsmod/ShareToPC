package github.linsmod.sharetopc.ui;

import github.linsmod.sharetopc.R;

/** 根据文件名扩展名返回对应的文件类型图标；未知类型回退到通用文件图标。 */
final class FileTypeIcon {

    private FileTypeIcon() {
    }

    static int forName(String name) {
        if (name == null) return R.drawable.ic_file;
        String ext = extension(name);
        switch (ext) {
            case "pdf":       return R.drawable.ic_type_pdf;
            case "doc":
            case "odt":       return R.drawable.ic_type_doc;
            case "docx":      return R.drawable.ic_type_docx;
            case "rtf":       return R.drawable.ic_type_rtf;
            case "xls":       return R.drawable.ic_type_xls;
            case "xlsx":      return R.drawable.ic_type_xlsx;
            case "csv":       return R.drawable.ic_type_csv;
            case "ppt":       return R.drawable.ic_type_ppt;
            case "pptx":      return R.drawable.ic_type_pptx;
            case "txt":
            case "md":
            case "ini":
            case "cfg":
            case "conf":
            case "properties": return R.drawable.ic_type_txt;
            case "log":       return R.drawable.ic_type_log;
            case "png":
            case "jpg":
            case "jpeg":
            case "gif":
            case "bmp":
            case "webp":
            case "ico":       return R.drawable.ic_type_img;
            case "svg":       return R.drawable.ic_type_svg;
            case "psd":       return R.drawable.ic_type_psd;
            case "ai":        return R.drawable.ic_type_ai;
            case "eps":       return R.drawable.ic_type_eps;
            case "raw":       return R.drawable.ic_type_raw;
            case "mp4":
            case "mkv":
            case "avi":
            case "mov":
            case "wmv":
            case "flv":
            case "webm":
            case "rm":
            case "rom":       return R.drawable.ic_type_video;
            case "sub":       return R.drawable.ic_type_sub;
            case "mp3":
            case "wav":
            case "flac":
            case "aac":
            case "ogg":
            case "m4a":
            case "wma":
            case "mid":
            case "midi":
            case "aif":       return R.drawable.ic_type_audio;
            case "zip":
            case "rar":
            case "7z":
            case "tar":
            case "gz":
            case "gzip":      return R.drawable.ic_type_zip;
            case "exe":
            case "msi":       return R.drawable.ic_type_exe;
            case "apk":       return R.drawable.ic_type_apk;
            case "html":
            case "htm":       return R.drawable.ic_type_html;
            case "xml":       return R.drawable.ic_type_xml;
            case "json":      return R.drawable.ic_type_json;
            case "sql":       return R.drawable.ic_type_sql;
            case "yaml":
            case "yml":       return R.drawable.ic_type_yaml;
            case "iso":       return R.drawable.ic_type_iso;
            case "dmg":       return R.drawable.ic_type_dmg;
            case "js":
            case "jsx":       return R.drawable.ic_type_js;
            case "ts":
            case "tsx":       return R.drawable.ic_type_ts;
            case "java":      return R.drawable.ic_type_java;
            case "py":
            case "pyw":       return R.drawable.ic_type_py;
            case "sh":
            case "bash":
            case "zsh":       return R.drawable.ic_type_sh;
            case "bat":
            case "cmd":       return R.drawable.ic_type_bat;
            case "css":
            case "scss":
            case "sass":
            case "less":      return R.drawable.ic_type_css;
            case "c":
            case "h":
            case "cpp":
            case "cc":
            case "cxx":
            case "hpp":       return R.drawable.ic_type_cpp;
            case "php":       return R.drawable.ic_type_php;
            case "dll":       return R.drawable.ic_type_dll;
            case "jar":       return R.drawable.ic_type_jar;
            case "bin":       return R.drawable.ic_type_bin;
            case "elf":       return R.drawable.ic_type_elf;
            case "dat":       return R.drawable.ic_type_dat;
            case "pdb":       return R.drawable.ic_type_pdb;
            case "3ds":       return R.drawable.ic_type_3ds;
            case "stl":       return R.drawable.ic_type_stl;
            case "dwg":       return R.drawable.ic_type_dwg;
            case "odg":       return R.drawable.ic_type_odg;
            case "eml":       return R.drawable.ic_type_eml;
            case "ics":       return R.drawable.ic_type_ics;
            case "rss":       return R.drawable.ic_type_rss;
            case "dem":       return R.drawable.ic_type_dem;
            case "ps":        return R.drawable.ic_type_ps;
            case "otf":
            case "ttf":
            case "woff":
            case "woff2":     return R.drawable.ic_type_font;
            case "epub":      return R.drawable.ic_type_ebook;
            default:          return R.drawable.ic_file;
        }
    }

    /** 取文件名最后一个点后的扩展名（小写、无点）；无扩展名返回空串。 */
    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return "";
        return name.substring(dot + 1).toLowerCase(java.util.Locale.getDefault());
    }
}
