using System;
using System.IO;
using System.IO.Compression;
using System.Net;

namespace ShareToPcReciver.Etp
{
    /// <summary>
    /// 负责在线下载 Everything 本体与 ETP 插件 zip，并解压到目标目录。
    /// 通过回调上报进度，方便向导显示。
    /// </summary>
    public sealed class EverythingDownloader
    {
        /// <summary>Everything 1.5 本体（x64 便携）下载地址。</summary>
        public const string EverythingZipUrl =
            "https://www.voidtools.com/Everything-1.5.0.1423b.x64.zip";

        /// <summary>ETP Server 插件（x64）下载地址。</summary>
        public const string EtpPluginZipUrl =
            "https://www.voidtools.com/Everything.ETP.Server-1.0.2.5.x64.zip";

        /// <summary>进度回调：上传已下载字节下载字节总数与阶段文案。</summary>
        public Action<string, long, long> Progress { get; set; }

        public EverythingDownloader()
        {
            // 兼容旧平台；HttpClient 也可，但 WebClient 在 4.8.1 里最简单。
            ServicePointManager.SecurityProtocol = SecurityProtocolType.Tls12;
        }

        /// <summary>从 URL 下载 zip 到指定文件（幂等：已存在且非空则跳过）。</summary>
        public void DownloadZip(string url, string destFile)
        {
            if (File.Exists(destFile) && new FileInfo(destFile).Length > 0)
            {
                Progress?.Invoke($"已存在 {Path.GetFileName(destFile)}，跳过下载", 100, 100);
                return;
            }

            using (var wc = new WebClient())
            {
                wc.DownloadProgressChanged += (s, e) =>
                    Progress?.Invoke("下载中 " + Path.GetFileName(destFile), e.BytesReceived, e.TotalBytesToReceive);
                Progress?.Invoke("开始下载 " + Path.GetFileName(destFile), 0, 1);
                wc.DownloadFile(url, destFile);
            }
            Progress?.Invoke("下载完成 " + Path.GetFileName(destFile), 100, 100);
        }

        /// <summary>解压 zip 到目录。安全解压：拒绝 zip-slip。</summary>
        public void ExtractZip(string zipPath, string targetDir)
        {
            Directory.CreateDirectory(targetDir);
            using (var archive = ZipFile.OpenRead(zipPath))
            {
                foreach (var entry in archive.Entries)
                {
                    string full = Path.Combine(targetDir, entry.FullName);
                    string targetFull = Path.GetFullPath(full);
                    string targetRoot = Path.GetFullPath(targetDir + Path.DirectorySeparatorChar);
                    if (!targetFull.StartsWith(targetRoot, StringComparison.OrdinalIgnoreCase))
                        throw new InvalidOperationException("解压路径越界：" + entry.FullName);

                    if (string.IsNullOrEmpty(entry.Name))
                    {
                        Directory.CreateDirectory(targetFull);
                        continue;
                    }
                    Directory.CreateDirectory(Path.GetDirectoryName(targetFull));
                    entry.ExtractToFile(targetFull, overwrite: true);
                }
            }
        }
    }
}