using System;
using System.IO;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace ShareToPcReciver.Storage
{
    public class ReceiveStorage
    {
        private readonly string _baseDir;
        private readonly object _lock = new object();

        public ReceiveStorage(string baseDir)
        {
            _baseDir = baseDir;
        }

        public string GetBaseDir() => _baseDir;

        private class PartMeta
        {
            public string FinalPath { get; set; } = "";
            public string FileName { get; set; } = "";
        }

        /// <summary>创建接收文件：返回最终文件路径，out 参数为写入用的 .part 临时路径（基于 transferId 命名）。</summary>
        public string CreateFile(string originalName, string transferId, out string partPath)
        {
            string safeName = SanitizeFileName(originalName);
            if (string.IsNullOrEmpty(safeName))
                safeName = "received_file";

            string monthDir = Path.Combine(_baseDir, DateTime.Now.ToString("yyyy-MM"));
            lock (_lock)
            {
                if (!Directory.Exists(monthDir))
                    Directory.CreateDirectory(monthDir);
            }

            string finalName = safeName;
            string finalPath = Path.Combine(monthDir, finalName);
            lock (_lock)
            {
                if (File.Exists(finalPath))
                {
                    finalName = AddSuffix(safeName);
                    finalPath = Path.Combine(monthDir, finalName);
                }
            }

            // .part 用 transferId 命名，避免同名文件碰撞
            string cleanId = SanitizeTransferId(transferId);
            partPath = Path.Combine(monthDir, cleanId + ".part");

            // 写入 .meta 记录最终路径和原始文件名（用于断点续传时恢复）
            string metaPath = partPath + ".meta";
            var meta = new PartMeta { FinalPath = finalPath, FileName = originalName };
            File.WriteAllText(metaPath, JsonSerializer.Serialize(meta), Encoding.UTF8);

            return finalPath;
        }

        /// <summary>将 .part 重命名为最终文件，清理 .meta。</summary>
        public void CompleteFile(string partPath)
        {
            string metaPath = partPath + ".meta";
            string finalPath = null;

            if (File.Exists(metaPath))
            {
                try
                {
                    var meta = JsonSerializer.Deserialize<PartMeta>(File.ReadAllText(metaPath, Encoding.UTF8));
                    finalPath = meta?.FinalPath;
                }
                catch { }
            }

            if (finalPath == null && partPath.EndsWith(".part"))
                finalPath = partPath.Substring(0, partPath.Length - 5);

            if (File.Exists(partPath))
            {
                if (File.Exists(finalPath))
                    File.Delete(finalPath);
                File.Move(partPath, finalPath);
            }

            try { if (File.Exists(metaPath)) File.Delete(metaPath); } catch { }
        }

        /// <summary>
        /// 通过 transferId 查找未完成的 .part 文件（用于断点续传）。
        /// 返回 true 表示找到，通过 out 返回 partPath、receivedBytes、finalPath、originalName。
        /// </summary>
        public bool FindPartFile(string transferId, out string partPath, out long receivedBytes,
                                 out string finalPath, out string originalName)
        {
            finalPath = null;
            originalName = null;
            string cleanId = SanitizeTransferId(transferId);
            if (string.IsNullOrEmpty(cleanId))
            {
                partPath = null;
                receivedBytes = 0;
                return false;
            }

            string partFileName = cleanId + ".part";

            if (Directory.Exists(_baseDir))
            {
                foreach (var dir in Directory.GetDirectories(_baseDir))
                {
                    string candidate = Path.Combine(dir, partFileName);
                    if (File.Exists(candidate))
                    {
                        partPath = candidate;
                        receivedBytes = new FileInfo(candidate).Length;

                        // 读取 .meta 恢复最终路径和文件名
                        string metaPath = candidate + ".meta";
                        if (File.Exists(metaPath))
                        {
                            try
                            {
                                var meta = JsonSerializer.Deserialize<PartMeta>(
                                    File.ReadAllText(metaPath, Encoding.UTF8));
                                finalPath = meta?.FinalPath;
                                originalName = meta?.FileName;
                            }
                            catch { }
                        }

                        return true;
                    }
                }
            }

            partPath = null;
            receivedBytes = 0;
            return false;
        }

        public void DiscardFile(string partPath)
        {
            try
            {
                string metaPath = partPath + ".meta";
                if (File.Exists(partPath))
                    File.Delete(partPath);
                if (File.Exists(metaPath))
                    File.Delete(metaPath);
            }
            catch { }
        }

        /// <summary>通过 transferId 查找并删除对应的 .part 及 .meta 文件。</summary>
        public void DiscardPartByTransferId(string transferId)
        {
            if (FindPartFile(transferId, out string partPath, out _, out _, out _))
                DiscardFile(partPath);
        }

        public void OpenFolder(string path)
        {
            string dir = null;
            try
            {
                dir = Path.GetDirectoryName(path);
                if (!string.IsNullOrEmpty(dir) && Directory.Exists(dir))
                {
                    System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo
                    {
                        FileName = dir,
                        UseShellExecute = true
                    });
                }
            }
            catch (Exception ex)
            {
                Util.Log.Error($"Failed to open folder: {dir}", ex);
            }
        }

        private static string SanitizeTransferId(string transferId)
        {
            if (string.IsNullOrWhiteSpace(transferId))
                return Guid.NewGuid().ToString("N");
            // 仅保留字母数字和 - _ .
            string clean = Regex.Replace(transferId, @"[^a-zA-Z0-9\-_\.]", "");
            return string.IsNullOrEmpty(clean) ? Guid.NewGuid().ToString("N") : clean;
        }

        private static string SanitizeFileName(string name)
        {
            if (string.IsNullOrWhiteSpace(name))
                return string.Empty;

            string invalid = new string(Path.GetInvalidFileNameChars());
            foreach (char c in invalid)
                name = name.Replace(c, '_');

            name = name.Trim();
            if (name.Length == 0)
                return string.Empty;
            if (name.Length > 200)
                name = name.Substring(0, 200);

            return name;
        }

        private static string AddSuffix(string fileName)
        {
            string dir = Path.GetDirectoryName(fileName) ?? "";
            string name = Path.GetFileNameWithoutExtension(fileName);
            string ext = Path.GetExtension(fileName);

            Regex regex = new Regex(@"^(.*?) \((\d+)\)$");
            var match = regex.Match(name);
            int n = 1;
            if (match.Success)
            {
                name = match.Groups[1].Value;
                n = int.Parse(match.Groups[2].Value) + 1;
            }

            return $"{name} ({n}){ext}";
        }
    }
}