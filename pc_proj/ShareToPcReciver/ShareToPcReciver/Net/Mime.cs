using System;
using Microsoft.Win32;

namespace ShareToPcReciver.Net
{
    /// <summary>通过 Windows 注册表（HKEY_CLASSES_ROOT 的 Content Type）获取文件 MIME，
    /// 比手机端按扩展名猜测更准（Office/WPS 安装后会注册准确的 Content Type）。</summary>
    public static class Mime
    {
        public static string FromFileName(string fileName)
        {
            if (string.IsNullOrEmpty(fileName)) return "";
            string ext = System.IO.Path.GetExtension(fileName).ToLowerInvariant();
            if (string.IsNullOrEmpty(ext)) return "";
            try
            {
                using var key = Registry.ClassesRoot.OpenSubKey(ext);
                string value = key?.GetValue("Content Type") as string;
                return string.IsNullOrEmpty(value) ? "" : value;
            }
            catch
            {
                return "";
            }
        }
    }
}
