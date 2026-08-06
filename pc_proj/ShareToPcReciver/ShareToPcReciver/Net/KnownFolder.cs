using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;

namespace ShareToPcReciver.Net
{
    /// <summary>
    /// 通过 Windows Shell API (SHGetKnownFolderPath) 获取真实用户目录路径。
    /// 相比拼接 "C:\Users\用户名\..."，能正确处理 OneDrive 重定向等真实位置。
    /// </summary>
    public static class KnownFolder
    {
        private static readonly Guid FOLDERID_Desktop = new Guid("B4BFCC3A-DB2C-424C-B029-7FE99A87C641");
        private static readonly Guid FOLDERID_Documents = new Guid("FDD39AD0-238F-46AF-ADB4-6C85480369C7");
        private static readonly Guid FOLDERID_Downloads = new Guid("374DE290-123F-4565-9164-39C4925E467B");
        private static readonly Guid FOLDERID_Videos = new Guid("18989B1D-99B5-455B-841C-AB7C74E4DDFC");
        private static readonly Guid FOLDERID_Pictures = new Guid("33E28130-4E1E-4676-835A-98395C3BC3BB");
        private static readonly Guid FOLDERID_Music = new Guid("4BD8D571-6D19-48D3-BE97-422220080E43");

        [DllImport("shell32.dll", CharSet = CharSet.Unicode)]
        private static extern int SHGetKnownFolderPath(
            [MarshalAs(UnmanagedType.LPStruct)] Guid rfid,
            uint dwFlags,
            IntPtr hToken,
            out IntPtr ppszPath);

        /// <summary>获取指定 KnownFolder 的真实路径；获取失败返回 null（不抛异常）。</summary>
        public static string GetPath(Guid folderId)
        {
            IntPtr ppszPath;
            int hr = SHGetKnownFolderPath(folderId, 0, IntPtr.Zero, out ppszPath);
            if (hr != 0)
                return null;
            try
            {
                return Marshal.PtrToStringUni(ppszPath);
            }
            finally
            {
                Marshal.FreeCoTaskMem(ppszPath);
            }
        }

        /// <summary>获取常用用户目录的真实路径，key 与安卓端约定一致（desktop/documents/...）。</summary>
        public static Dictionary<string, string> GetAll()
        {
            return new Dictionary<string, string>
            {
                ["desktop"] = GetPath(FOLDERID_Desktop),
                ["documents"] = GetPath(FOLDERID_Documents),
                ["downloads"] = GetPath(FOLDERID_Downloads),
                ["videos"] = GetPath(FOLDERID_Videos),
                ["pictures"] = GetPath(FOLDERID_Pictures),
                ["music"] = GetPath(FOLDERID_Music)
            };
        }
    }
}
