using System;
using System.IO;
using System.Text;
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

        /// <summary>创建接收文件：返回最终文件路径，out 参数为写入用的 .part 临时路径。</summary>
        public string CreateFile(string originalName, out string partPath)
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
            string targetPath = Path.Combine(monthDir, finalName);
            lock (_lock)
            {
                if (File.Exists(targetPath))
                {
                    finalName = AddSuffix(safeName);
                    targetPath = Path.Combine(monthDir, finalName);
                }
            }

            partPath = targetPath + ".part";
            return targetPath;
        }

        public void CompleteFile(string partPath)
        {
            if (partPath.EndsWith(".part") && File.Exists(partPath))
            {
                string finalPath = partPath.Substring(0, partPath.Length - 5);
                if (File.Exists(finalPath))
                    File.Delete(finalPath);
                File.Move(partPath, finalPath);
            }
        }

        public void DiscardFile(string partPath)
        {
            try
            {
                if (File.Exists(partPath))
                    File.Delete(partPath);
                else if (partPath.EndsWith(".part"))
                {
                    string finalPath = partPath.Substring(0, partPath.Length - 5);
                    if (File.Exists(finalPath))
                        File.Delete(finalPath);
                }
            }
            catch { }
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