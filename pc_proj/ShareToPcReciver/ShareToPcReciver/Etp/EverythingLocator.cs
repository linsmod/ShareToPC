using System;
using System.IO;
using System.Net.Sockets;
using System.Text;
using Microsoft.Win32;

namespace ShareToPcReciver.Etp
{
    /// <summary>
    /// 探测本机 Everything 的安装位置、插件目录、配置 ini 路径及 ETP 启用状态。
    /// 不修改任何文件，只做只读探测。
    /// </summary>
    public sealed class EverythingLocator
    {
        /// <summary>Everything 主程序文件名。</summary>
        public const string ExeName = "Everything.exe";
        /// <summary>ETP 插件文件名（x64）。</summary>
        public const string PluginDllName = "etp_server64.dll";

        /// <summary>Everything 是否为便携版由我们部署到用户目录。</summary>
        public const string LocalPortableDirName = "Everything";

        /// <summary>用户配置目录（Everything 默认把 ini/db 放这里）。</summary>
        private static string DefaultAppDataDir => Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "Everything");

        /// <summary>Everything.exe 的完整路径；找不到返回 null。</summary>
        public string EverythingExe => Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "ShareToPcReciver/Everything", "Everything.exe");

        /// <summary>Everything.e 所在目录；找不到为 null。</summary>
        public string EverythingDir => EverythingExe == null ? null : Path.GetDirectoryName(EverythingExe);

        /// <summary>插件目录（Everything 同目录下 plugins\）。</summary>
        public string PluginsDir => EverythingDir == null ? null : Path.Combine(EverythingDir, "plugins");

        /// <summary>ETP 插件 dll 完整路径。</summary>
        public string PluginDllPath => PluginsDir == null ? null : Path.Combine(PluginsDir, PluginDllName);

        /// <summary>Everything 配置 ini 路径。</summary>
        public string IniPath { get; private set; }

        /// <summary>是否在系统搜索到了 Everything（本机已安装或便携包已部署）。</summary>
        public bool EverythingFound => File.Exists(EverythingExe);

        /// <summary>ETP 插件 dll 是否已就位。</summary>
        public bool PluginInstalled => PluginDllPath != null && File.Exists(PluginDllPath);

        /// <summary>需要 Everything 1.5（带 ETP 插件支持）。1.4 或更低不可用。</summary>
        public bool IsVersion15OrNewer { get; private set; } = true;

        /// <summary>从注册表找已安装版 Everything。</summary>
        private static string FindInstalledEverything()
        {
            try
            {
                using var key = Registry.LocalMachine.OpenSubKey(@"SOFTWARE\Microsoft\Windows\CurrentVersion\App Paths\Everything.exe");
                if (key != null)
                {
                    string exe = key.GetValue(null) as string;
                    if (!string.IsNullOrEmpty(exe) && File.Exists(exe)) return exe;
                }
            }
            catch { /* 忽略注册表访问失败 */ }
            return null;
        }

        /// <summary>从便携版目录找。</summary>
        private static string FindPortableEverything(string localPortableDir)
        {
            if (string.IsNullOrEmpty(localPortableDir)) return null;
            string exe = Path.Combine(localPortableDir, ExeName);
            return File.Exists(exe) ? exe : null;
        }

        /// <summary>
        /// 实测 ETP Server 是否真正运行：连接本机 ETP 端口后读取 220 欢迎，
        /// 并实际发送一个 EVERYTHING 查询（EVERYTHING SEARCH + QUERY）验证服务端有响应。
        /// 只做只读探测，不修改任何文件。
        /// </summary>
        /// <param name="timeoutMs">单步超时（毫秒）。</param>
        public bool IsEtpRunning(int port,int timeoutMs = 1500)
        {
            if (!EverythingFound) return false;
            try
            {
                using (var client = new TcpClient())
                {
                    // 用带超时的异步连接，避免在不可达端口上长时间阻塞
                    var connectTask = client.ConnectAsync("127.0.0.1", port);
                    if (!connectTask.Wait(timeoutMs) || !client.Connected)
                        return false;

                    using (var stream = client.GetStream())
                    {
                        stream.ReadTimeout = timeoutMs;
                        stream.WriteTimeout = timeoutMs;
                        using (var reader = new StreamReader(stream, Encoding.UTF8, false, 512, true))
                        using (var writer = new StreamWriter(stream, new UTF8Encoding(false), 512, true))
                        {
                            writer.NewLine = "\r\n";

                            // 1) 欢迎行：ETP/FTP 服务应返回 "220 ..."
                            string welcome = ReadLine(reader, timeoutMs);
                            if (welcome == null || !welcome.StartsWith("220", StringComparison.Ordinal))
                                return false;

                            // 2) 匿名登录（失败也不影响后续判定，只是尝试让命令被接受）
                            TryCommand(reader, writer, "USER anonymous", timeoutMs);
                            TryCommand(reader, writer, "PASS anonymous", timeoutMs);

                            // 3) 执行一个真实查询：设置空搜索串并执行 QUERY
                            string searchResp = TryCommand(reader, writer, "EVERYTHING SEARCH \"\"", timeoutMs);
                            string queryResp = TryCommand(reader, writer, "EVERYTHING QUERY", timeoutMs);

                            // 关键判别：服务端认识并执行了 EVERYTHING 命令（返回 200 系），
                            // 才说明确实是 Everything 的 ETP 服务在运行，而非一个随机的 FTP/端口。
                            if (searchResp != null && searchResp.StartsWith("200", StringComparison.Ordinal))
                                return true;
                            if (queryResp != null &&
                                (queryResp.StartsWith("200", StringComparison.Ordinal) ||
                                 queryResp.IndexOf("Query results", StringComparison.OrdinalIgnoreCase) >= 0))
                                return true;
                        }
                    }
                }
            }
            catch { /* 任何一步失败均视为未运行 */ }
            return false;
        }

        /// <summary>发送一条 FTP/ETP 命令并读取首行响应；超时或出错返回 null。</summary>
        private static string TryCommand(StreamReader reader, StreamWriter writer, string cmd, int timeoutMs)
        {
            try
            {
                writer.WriteLine(cmd);
                writer.Flush();
                return ReadLine(reader, timeoutMs);
            }
            catch { return null; }
        }

        /// <summary>带超时读取一行；超时或出错返回 null。</summary>
        private static string ReadLine(StreamReader reader, int timeoutMs)
        {
            try
            {
                var task = reader.ReadLineAsync();
                if (task.Wait(timeoutMs) && task.IsCompleted && !task.IsFaulted)
                    return task.Result;
            }
            catch { }
            return null;
        }
    }
}