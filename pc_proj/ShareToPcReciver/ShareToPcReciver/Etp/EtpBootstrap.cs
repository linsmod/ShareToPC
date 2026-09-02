using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Threading;
using ShareToPcReciver.Util;

namespace ShareToPcReciver.Etp
{
    /// <summary>配置流程中各步骤的状态。</summary>
    public enum EtpStepStatus
    {
        /// <summary>尚未开始。</summary>
        Waiting,
        /// <summary>正在执行。</summary>
        Running,
        /// <summary>执行成功。</summary>
        Done,
        /// <summary>执行失败。</summary>
        Failed
    }

    /// <summary>配置流程中的一个步骤（checklist 中的一项）。</summary>
    public sealed class EtpStep
    {
        public int Index { get; }
        public string Title { get; }
        public EtpStepStatus Status { get; set; } = EtpStepStatus.Waiting;
        public string Detail { get; set; }

        public EtpStep(int index, string title)
        {
            Index = index;
            Title = title;
        }
    }

    /// <summary>“启用 ETP 远程访问”配置流程的结果（含分步状态与汇总信息）。</summary>
    public sealed class EtpEnableResult
    {
        /// <summary>全部步骤是否成功。</summary>
        public bool Success { get; set; }
        /// <summary>步骤清单（checklist 数据源）。</summary>
        public IReadOnlyList<EtpStep> Steps { get; }
        /// <summary>Everything.exe 路径。</summary>
        public string EverythingExe { get; set; }
        /// <summary>ETP 服务端口。</summary>
        public int EtpPort { get; set; } = 21;
        /// <summary>汇总文案（成功时为给用户的总结）。</summary>
        public string Summary { get; set; }

        public EtpEnableResult(IReadOnlyList<EtpStep> steps)
        {
            Steps = steps;
        }

        /// <summary>汇总失败时，返回首个失败步骤的说明；无则给定默认文案。</summary>
        public string FailureReason()
        {
            var failed = Steps.FirstOrDefault(s => s.Status == EtpStepStatus.Failed);
            return failed != null ? failed.Title + "：" + (failed.Detail ?? "未知错误") : "配置未完成";
        }
    }

    /// <summary>
    /// 编排“启用 ETP 远程访问”完整流程，逐步骤上报状态：
    ///   检测 Everything → 确保本体（缺失则在线下载）→ 安装 ETP 插件 → 启用 ETP 配置 → 启动 Everything。
    /// </summary>
    public sealed class EtpBootstrap
    {
        /// <summary>我们自分发便携 Everything 的根目录（用户目录）。</summary>
        public static string LocalRoot =>
            Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                         "ShareToPcReciver");

        public static string PortableDir => Path.Combine(LocalRoot, EverythingLocator.LocalPortableDirName);

        /// <summary>URL：Everything 1.5 本体（x64 便携）。</summary>
        public const string EverythingZipUrl =
            "https://www.voidtools.com/Everything-1.5.0.1423b.x64.zip";
        /// <summary>URL：ETP Server 插件（x64）。</summary>
        public const string EtpPluginZipUrl =
            "https://www.voidtools.com/Everything.ETP.Server-1.0.2.5.x64.zip";

        /// <summary>新版 Everything（1.5+）使用插件独立 ini 存放 ETP 配置，随程序分发并部署到 Everything 目录。</summary>
        public const string PluginIniName = "Plugins-Everything_ShareToPc.ini";

        /// <summary>步骤状态变化回调：(变化的步骤, 当前已完成数)。UI 据此刷新 checklist。</summary>
        public Action<EtpStep, int> StepChanged { get; set; }

        private readonly EverythingLocator _locator = new EverythingLocator();
        private readonly EverythingDownloader _downloader = new EverythingDownloader();

        public static readonly List<EtpStep> _steps = new List<EtpStep>
        {
            new EtpStep(0, "检测 Everything"),
            new EtpStep(1, "确保 Everything 本体"),
            new EtpStep(2, "安装 ETP 插件"),
            new EtpStep(3, "启用 ETP 配置"),
            new EtpStep(4, "启动 Everything"),
            new EtpStep(5, "测试 ETP连接测试")
        };

        public EtpBootstrap()
        {
            _downloader.Progress += OnDownloadProgress;
        }

        public EtpEnableResult Run(bool autoDownload)
        {
            foreach (var s in _steps)
            {
                s.Status = EtpStepStatus.Waiting;
                s.Detail = null;
            }
            RefreshAll();

            try
            {
                RunDetect();

                // 若 ETP 服务此刻已在运行，说明 Everything 本体、插件、配置均就绪，
                // 后续下载/安装/启用配置/启动步骤可直接减免（跳过）。
                if (_locator.IsEtpRunning(GetEtpPort()))
                {
                    MarkRemainingAsSkipped();
                    RefreshAll();
                    return BuildResult(true);
                }

                RunEnsureEverything(autoDownload);
                RunInstallPlugin(autoDownload);
                RunEnableConfig();
                RunLaunch();
                TestRunning();
                RefreshAll();
                return BuildResult(_locator.IsEtpRunning(GetEtpPort()));
            }
            catch (Exception ex)
            {
                // 若有步骤停在 Running，标为 Failed；其余未开始的保持 Waiting
                var running = _steps.FirstOrDefault(s => s.Status == EtpStepStatus.Running);
                if (running != null)
                {
                    running.Status = EtpStepStatus.Failed;
                    running.Detail = ex.Message;
                }
                RefreshAll();
                return BuildResult(false);
            }
        }

        // ---- 各步骤实现 ----

        private void RunDetect()
        {
            SetRunning(_steps[0], "扫描本机与便携目录");
            if (_locator.EverythingFound)
                SetDone(_steps[0], _locator.EverythingExe);
            else
                SetDone(_steps[0], "未发现，将自动安装");
        }

        private void RunEnsureEverything(bool autoDownload)
        {
            if (_locator.EverythingFound)
            {
                SetDone(_steps[1], "已存在：" + _locator.EverythingExe);
                return;
            }
            if (!autoDownload)
                throw new InvalidOperationException("未检测到 Everything，且未允许自动下载。");

            SetRunning(_steps[1], "正在下载 Everything…");
            string zip = DownloadTo(EverythingZipUrl, "Everything.zip");
            SetRunning(_steps[1], "正在解压到便携目录…");
            _downloader.ExtractZip(zip, PortableDir);
            if (!_locator.EverythingFound)
                throw new InvalidOperationException("Everything 下载/解压后仍未找到可执行文件。");
            SetDone(_steps[1], "已安装到：" + _locator.EverythingExe);
        }

        private void RunInstallPlugin(bool autoDownload)
        {
            if (_locator.PluginInstalled)
            {
                SetDone(_steps[2], "已就位：" + _locator.PluginDllPath);
                return;
            }
            if (!autoDownload)
                throw new InvalidOperationException("缺少 ETP 插件，且未允许自动下载。");

            SetRunning(_steps[2], "正在下载 ETP 插件…");
            string zip = DownloadTo(EtpPluginZipUrl, "EtpServer.zip");
            string pluginDir = Path.Combine(LocalRoot, "EtpPlugin");
            SetRunning(_steps[2], "正在解压插件…");
            _downloader.ExtractZip(zip, pluginDir);
            SetRunning(_steps[2], "正在复制插件到 plugins…");
            InstallPluginFrom(pluginDir);
            if (!_locator.PluginInstalled)
                throw new InvalidOperationException("ETP 插件已下载，但未能放入 Everything plugins 目录。");
            SetDone(_steps[2], "已安装：" + _locator.PluginDllPath);
        }

        private void RunEnableConfig()
        {
            SetRunning(_steps[3], "安装ETP插件预设");
            SetEtpEnabled(true);
            SetDone(_steps[3], "ETP 服务已启用（端口 " + PluginPortOrDefault + "）");
        }

        private void RunLaunch()
        {
            SetRunning(_steps[4], "正在启动 Everything…");
            LaunchEverything();
            Thread.Sleep(1000);
            var processes = Process.GetProcessesByName("Everything");
            SetDone(_steps[4], processes.Length>0?"Everything 已启动": "Everything 启动失败");
        }

        private bool TestRunning()
        {
            SetRunning(_steps[5], "连接测试…");
            var running = _locator.IsEtpRunning(GetEtpPort(), 30000);
            SetDone(_steps[5], running ?"ETP连接就绪":"ETP连接测试失败");
            return running;
        }

        private EtpEnableResult BuildResult(bool success)
        {
            var result = new EtpEnableResult(_steps)
            {
                Success = success,
                EverythingExe = _locator.EverythingExe,
                EtpPort = PluginPortOrDefault
            };
            if (success)
            {
                result.Summary = $"Everything：{_locator.EverythingExe}\n"
                    + $"ETP 端口：{PluginPortOrDefault}\n"
                    + "手机端现在可通过局域网搜索/浏览本机文件。";
            }
            else
            {
                result.Summary = "配置未完成。\n" + result.FailureReason();
            }
            return result;
        }

        private void SetRunning(EtpStep step, string detail)
        {
            step.Status = EtpStepStatus.Running;
            step.Detail = detail;
            RefreshAll();
        }

        private void SetDone(EtpStep step, string detail)
        {
            step.Status = detail.Contains("失败")?EtpStepStatus.Failed: EtpStepStatus.Done;
            step.Detail = detail;
            RefreshAll();
        }

        /// <summary>ETP 已在运行时，把步骤 1..N（本体/插件/配置/启动）全部标记为“已跳过”。</summary>
        private void MarkRemainingAsSkipped()
        {
            for (int i = 1; i < _steps.Count; i++)
            {
                _steps[i].Status = EtpStepStatus.Done;
                _steps[i].Detail = "ETP 已在运行，此步已减免";
            }
        }

        private void RefreshAll()
        {
            var done = _steps.Count(s => s.Status == EtpStepStatus.Done);
            foreach (var s in _steps)
                StepChanged?.Invoke(s, done);
        }

        private void OnDownloadProgress(string text, long downloaded, long total)
        {
            var step = _steps.FirstOrDefault(s => s.Status == EtpStepStatus.Running);
            if (step == null) return;
            step.Detail = text;
            RefreshAll();
        }

        private string DownloadTo(string url, string fileName)
        {
            string dir = Path.Combine(LocalRoot, "cache");
            Directory.CreateDirectory(dir);
            string zip = Path.Combine(dir, fileName);
            _downloader.DownloadZip(url, zip);
            return zip;
        }

        /// <summary>在插件解压目录里递归找 etp_server 插件 dll，复制到 Everything\plugins\。</summary>
        private void InstallPluginFrom(string dir)
        {
            string dll = Directory.EnumerateFiles(dir, "etp_server*.dll", SearchOption.AllDirectories)
                .FirstOrDefault(f => !f.ToLowerInvariant().Contains("setup"));
            if (dll == null)
                dll = Directory.EnumerateFiles(dir, "*.dll", SearchOption.AllDirectories)
                    .FirstOrDefault();
            if (dll == null)
                throw new InvalidOperationException("插件压缩包中未找到插件 dll。");

            string pluginsDir = _locator.PluginsDir;
            if (string.IsNullOrEmpty(pluginsDir))
                throw new InvalidOperationException("无法确定 Everything 的 plugins 目录。");
            Directory.CreateDirectory(pluginsDir);
            string target = Path.Combine(pluginsDir, EverythingLocator.PluginDllName);
            File.Copy(dll, target, overwrite: true);
        }

        /// <summary>
        /// 启用 ETP 配置：把随程序分发的插件预设 ini（Plugins-Everything_ShareToPc.ini）拷贝到 Everything 主目录。
        /// 新版 Everything 使用插件独立 ini，无需再改写全局 Everything.ini 的 allow_etp_server。
        /// </summary>
        public void SetEtpEnabled(bool enable,bool? enableDownload=null)
        {
            string everythingDir = _locator.EverythingDir;
            if (string.IsNullOrEmpty(everythingDir))
                everythingDir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "ShareToPcReciver", "Everything");

            // 源：随程序分发（输出目录）的插件预设 ini；缺省则回退到 Everything 目录里的同名文件。
            string source = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, PluginIniName);
            if (!File.Exists(source))
                source = Path.Combine(everythingDir, PluginIniName);
            if (!File.Exists(source))
                throw new InvalidOperationException("缺少插件预设文件 " + PluginIniName + "。");

            Directory.CreateDirectory(everythingDir);
            string target = Path.Combine(everythingDir, PluginIniName);
            if (!File.Exists(target)) { 
                File.Copy(source, target, overwrite: true);
            }
            var contents = File.ReadAllLines(target, System.Text.Encoding.UTF8);
            int changed = 0;
            for (int i = 0; i < contents.Length; i++)
            {
                var line = contents[i];
                if (line.StartsWith("enabled="))
                {
                    contents[i] = "enabled="+ (enable ? "1" : "0");
                    changed += line != contents[i] ? 1 : 0;
                }
                if (enableDownload.HasValue && line.StartsWith("allow_file_download=")) {
                    contents[i] = "allow_file_download=" + (enableDownload.Value ? "1" : "0");
                    changed += line != contents[i]?1:0;
                }
            }
            if(changed>0)
                File.WriteAllText(target, String.Join("\n", contents));
            Log.Info("ETP 插件配置已部署到 " + target);
        }
        private String ensureIni()
        {
            string everythingDir = _locator.EverythingDir;
            if (string.IsNullOrEmpty(everythingDir))
                return null;

            // 源：随程序分发（输出目录）的插件预设 ini；缺省则回退到 Everything 目录里的同名文件。
            string source = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, PluginIniName);
            if (!File.Exists(source))
                source = Path.Combine(everythingDir, PluginIniName);
            if (!File.Exists(source))
                return null;

            Directory.CreateDirectory(everythingDir);
            string target = Path.Combine(everythingDir, PluginIniName);
            if (!File.Exists(target))
            {
                File.Copy(source, target, overwrite: true);
            }
            return target;
        }
        public bool IsEtpDownloadAllowed()
        {
            String target = ensureIni();
            if (String.IsNullOrEmpty(target)) return false;
            var contents = File.ReadAllLines(target, System.Text.Encoding.UTF8);
            bool enable = false;
            for (int i = 0; i < contents.Length; i++)
            {
                var line = contents[i];
                if (line.StartsWith("enabled="))
                {
                    enable = line.Split('=')[1].Trim() == "1";
                }
                if (enable && line.StartsWith("allow_file_download="))
                {
                    return line.Split('=')[1].Trim() == "1";
                }
            }
            return false;
        }
        public int GetEtpPort()
        {
            String target = ensureIni();
            if (String.IsNullOrEmpty(target)) return 21;
            var contents = File.ReadAllLines(target, System.Text.Encoding.UTF8);
            for (int i = 0; i < contents.Length; i++)
            {
                var line = contents[i];
                if (line.StartsWith("port="))
                {
                    if (int.TryParse(line.Split('=')[1], out var port) && port >= 21 && port <= 65534) {
                        return port;
                    }
                }
            }
            return 21;
        }
        public int SetEtpPort(int port)
        {
            if (port < 21)
            {
                port = 21;
            }
            else if (port > 62234) {
                port = 21;
            }
            String target = ensureIni();
            if (String.IsNullOrEmpty(target)) return 21;
            var contents = File.ReadAllLines(target, System.Text.Encoding.UTF8);
            for (int i = 0; i < contents.Length; i++)
            {
                var line = contents[i];
                if (line.StartsWith("port="))
                {
                    contents[i] = "port=" + port;
                }
            }
            return port;
        }
        public bool IsEtpPluginEnabled()
        {
            string everythingDir = _locator.EverythingDir;
            if (string.IsNullOrEmpty(everythingDir))
                return false;

            // 源：随程序分发（输出目录）的插件预设 ini；缺省则回退到 Everything 目录里的同名文件。
            string source = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, PluginIniName);
            if (!File.Exists(source))
                source = Path.Combine(everythingDir, PluginIniName);
            if (!File.Exists(source))
                return false;

            Directory.CreateDirectory(everythingDir);
            string target = Path.Combine(everythingDir, PluginIniName);
            if (!File.Exists(target))
            {
                File.Copy(source, target, overwrite: true);
            }
            var contents = File.ReadAllLines(target, System.Text.Encoding.UTF8);
            bool enable = false;
            for (int i = 0; i < contents.Length; i++)
            {
                var line = contents[i];
                if (line.StartsWith("enabled="))
                {
                    return line.Split('=')[1].Trim() == "1";
                }
            }
            return false;
        }

        /// <summary>从已部署的插件 ini 读取 ETP 端口；缺省或非法时返回默认 21。</summary>
        private int PluginPortOrDefault
        {
            get
            {
                string target = _locator.EverythingDir == null
                    ? null
                    : Path.Combine(_locator.EverythingDir, PluginIniName);
                if (target == null || !File.Exists(target)) return 21;
                try
                {
                    foreach (string raw in File.ReadAllLines(target))
                    {
                        string line = raw.Trim();
                        if (line.StartsWith(";") || line.StartsWith("#")) continue;
                        // 端口键位于 [etp_server64.dll] 段内，取 port=…
                        if (line.StartsWith("port", StringComparison.OrdinalIgnoreCase))
                        {
                            int eq = line.IndexOf('=');
                            if (eq < 0) continue;
                            if (int.TryParse(line.Substring(eq + 1).Trim(), out int p) && p > 0 && p <= 65535)
                                return p;
                        }
                    }
                }
                catch { }
                return 21;
            }
        }
        public void RestartEverything()
        {
            if (string.IsNullOrEmpty(_locator.EverythingExe))
                throw new InvalidOperationException("Everything.exe 路径为空。");
            try
            {
                var psi = new ProcessStartInfo
                {
                    FileName = _locator.EverythingExe,
                    WorkingDirectory = _locator.EverythingDir,
                    UseShellExecute = true,
                    Arguments = "-exit -instance Everything_ShareToPc",
                };
                Process.Start(psi);
            }
            catch (Exception ex)
            {
                Log.Error("Failed to Exit Everything", ex);
                throw new InvalidOperationException("启动 Everything 失败：" + ex.Message, ex);
            }
            LaunchEverything();
        }
        private void LaunchEverything()
        {
            if (string.IsNullOrEmpty(_locator.EverythingExe))
                throw new InvalidOperationException("Everything.exe 路径为空。");
            try
            {
                var psi = new ProcessStartInfo
                {
                    FileName = _locator.EverythingExe,
                    WorkingDirectory = _locator.EverythingDir,
                    UseShellExecute = true,
                    Arguments = "-startup -instance Everything_ShareToPc",
                };
                Process.Start(psi);
            }
            catch (Exception ex)
            {
                Log.Error("Failed to launch Everything", ex);
                throw new InvalidOperationException("启动 Everything 失败：" + ex.Message, ex);
            }
        }
    }
}