using System;
using System.Collections.Generic;
using System.IO;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace ShareToPcReciver.Storage
{
    public class ReceiveStorage
    {
        private readonly string _baseDir;
        /// <summary>未完成传输的 .part/.meta 临时目录（系统临时目录下），避免弄乱接收文件夹。</summary>
        private readonly string _partDir;
        private readonly object _lock = new object();

        public ReceiveStorage(string baseDir)
        {
            _baseDir = baseDir;
            _partDir = Path.Combine(Path.GetTempPath(), "ShareToPcReciver", "parts");
        }

        public string GetBaseDir() => _baseDir;

        private class PartMeta
        {
            public string FinalPath { get; set; } = "";
            public string FileName { get; set; } = "";
        }

        /// <summary>创建接收文件：返回预期最终文件路径（接收目录），out 参数为写入用的 .part 临时路径（系统临时目录，基于 transferId 命名）。</summary>
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
                if (!Directory.Exists(_partDir))
                    Directory.CreateDirectory(_partDir);

                string finalPath = Path.Combine(monthDir, safeName);

                // .part 用 transferId 命名，避免同名文件碰撞，存放于系统临时目录
                string cleanId = SanitizeTransferId(transferId);
                partPath = Path.Combine(_partDir, cleanId + ".part");

                // 写入 .meta 记录预期路径和原始文件名（用于断点续传时恢复）
                string metaPath = partPath + ".meta";
                var meta = new PartMeta { FinalPath = finalPath, FileName = originalName };
                File.WriteAllText(metaPath, JsonSerializer.Serialize(meta), Encoding.UTF8);

                return finalPath;
            }
        }

        /// <summary>将 .part 重命名为最终文件（如有冲突自动加 (n) 后缀），清理 .meta。返回实际最终路径。</summary>
        public string CompleteFile(string partPath)
        {
            string metaPath = partPath + ".meta";
            string finalPath = null;
            string originalName = "";

            if (File.Exists(metaPath))
            {
                try
                {
                    var meta = JsonSerializer.Deserialize<PartMeta>(File.ReadAllText(metaPath, Encoding.UTF8));
                    finalPath = meta?.FinalPath;
                    originalName = meta?.FileName;
                }
                catch { }
            }

            if (finalPath == null && partPath.EndsWith(".part"))
                finalPath = partPath.Substring(0, partPath.Length - 5);

            if (File.Exists(partPath))
            {
                // 下载完成时检查文件名冲突，有冲突则追加 (n) 后缀
                string dir = Path.GetDirectoryName(finalPath);
                string finalName = Path.GetFileName(finalPath);
                lock (_lock)
                {
                    var takenNames = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
                    if (Directory.Exists(dir))
                    {
                        foreach (var f in Directory.GetFiles(dir))
                            takenNames.Add(Path.GetFileName(f));
                    }
                    string availableName = FindAvailableName(finalName, takenNames);
                    if (availableName != finalName)
                        finalPath = Path.Combine(dir, availableName);

                    if (File.Exists(finalPath))
                        File.Delete(finalPath);
                    // 临时目录可能和接收目录不在同一盘符，跨卷时先复制再删除
                    try
                    {
                        File.Move(partPath, finalPath);
                    }
                    catch (IOException)
                    {
                        File.Copy(partPath, finalPath, true);
                        File.Delete(partPath);
                    }
                }
            }

            try { if (File.Exists(metaPath)) File.Delete(metaPath); } catch { }

            return finalPath;
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

            string candidate = Path.Combine(_partDir, partFileName);
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
                    var openPath = File.Exists(path) ? path : dir;
                    System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo
                    {
                        FileName = "explorer.exe",
                        Arguments = $"/select,\"{openPath}\"",
                        UseShellExecute = false
                    });
                }
            }
            catch (Exception ex)
            {
                Util.Log.Error($"Failed to open folder: {dir}", ex);
            }
        }

        /// <summary>将文本保存为 .txt 文件，文件名取自文本内容。返回最终文件路径。</summary>
        public string SaveTextContent(string text)
        {
            string monthDir = Path.Combine(_baseDir, DateTime.Now.ToString("yyyy-MM"));
            lock (_lock)
            {
                if (!Directory.Exists(monthDir))
                    Directory.CreateDirectory(monthDir);
            }

            // 从文本生成文件名：取第一行（或前 60 字符），替换非法字符
            string rawName = text.Trim();
            rawName = rawName.Length > 60 ? rawName.Substring(0, 60) : rawName;
            int lineBreak = rawName.IndexOfAny(new[] { '\r', '\n' });
            if (lineBreak > 0) rawName = rawName.Substring(0, lineBreak);
            string safeName = SanitizeFileName(rawName);
            if (string.IsNullOrEmpty(safeName))
                safeName = "text";
            safeName += ".txt";

            string finalPath;
            lock (_lock)
            {
                var takenNames = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
                if (Directory.Exists(monthDir))
                {
                    foreach (var f in Directory.GetFiles(monthDir))
                        takenNames.Add(Path.GetFileName(f));
                }
                string availableName = FindAvailableName(safeName, takenNames);
                finalPath = Path.Combine(monthDir, availableName);
                File.WriteAllText(finalPath, text, Encoding.UTF8);
            }

            Util.Log.Info($"TEXT saved: {finalPath}");
            return finalPath;
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

        /// <summary>在文件名冲突时追加（n）后缀，如 file（2）.txt、file（3）.txt，直到找到不冲突的名字。</summary>
        private static string FindAvailableName(string baseName, HashSet<string> takenNames)
        {
            if (!takenNames.Contains(baseName))
                return baseName;

            string nameWithoutExt = Path.GetFileNameWithoutExtension(baseName);
            string ext = Path.GetExtension(baseName);

            int n = 2;
            string candidate;
            do
            {
                candidate = $"{nameWithoutExt} ({n}){ext}";
                n++;
            } while (takenNames.Contains(candidate));

            return candidate;
        }
    }
}