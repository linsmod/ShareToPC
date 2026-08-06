using System;
using System.IO;
using System.Reflection;
using System.Text.Json;
using System.Text.Json.Serialization;
using Microsoft.Win32;
using ShareToPcReciver.Net;

namespace ShareToPcReciver.Config
{
    /// <summary>内容到达时的自动动作。</summary>
    [JsonConverter(typeof(JsonStringEnumConverter))]
    public enum ArrivalAction
    {
        /// <summary>无操作</summary>
        None,
        /// <summary>复制文本到剪贴板</summary>
        CopyText,
        /// <summary>复制文件到剪贴板</summary>
        CopyFile,
        /// <summary>复制文件路径到剪贴板</summary>
        CopyFilePath,
        /// <summary>打开文件</summary>
        OpenFile,
        /// <summary>打开所在文件夹</summary>
        OpenDir
    }

    public class AppConfig
    {
        private static readonly string ConfigPath = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "config.json");
        private static readonly object LockObj = new object();

        public int Port { get; set; } = Protocol.DefaultPort;
        public string SaveDir { get; set; } = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.MyDocuments), "ShareToPC");
        /// <summary>收到文本时的动作（适用：None / CopyText）。</summary>
        public ArrivalAction TextAction { get; set; } = ArrivalAction.CopyText;
        /// <summary>收到文件时的动作（适用：None / CopyFile / CopyFilePath / OpenFile / OpenDir）。</summary>
        public ArrivalAction FileAction { get; set; } = ArrivalAction.None;
        public bool ShowBalloon { get; set; } = true;
        public bool RunAtStartup { get; set; } = false;
        /// <summary>心跳间隔（毫秒），服务端据此发送 BEAT，并下发给客户端。默认 30000ms。</summary>
        public int HeartbeatIntervalMs { get; set; } = 30000;
        /// <summary>本机设备唯一 ID，首次启动自动生成。</summary>
        public string DeviceId { get; set; } = Guid.NewGuid().ToString();

        // ---- 拉取（手机端从 PC 拉文件） ----

        /// <summary>是否允许手机端拉取文件。</summary>
        public bool PullEnabled { get; set; } = true;
        /// <summary>允许拉取的文件扩展名（逗号分隔，不含点），空表示仅允许列表为空。</summary>
        public string PullAllowedExtensions { get; set; } =
            "pdf,doc,docx,xls,xlsx,ppt,pptx,txt,md,csv,json,xml,jpg,jpeg,png,gif,bmp,webp,svg,mp3,wav,flac,ogg,m4a,mp4,mkv,avi,mov,zip,7z,rar,tar,gz,apk";
        /// <summary>单文件拉取大小上限（字节），0 表示不限制。默认 512MB。</summary>
        public long PullMaxBytes { get; set; } = 512L * 1024 * 1024;

        public static AppConfig Load()
        {
            if (!File.Exists(ConfigPath))
            {
                var cfg = new AppConfig();
                cfg.Save(); // 首次启动持久化设备配置（含 DeviceId）
                return cfg;
            }

            try
            {
                string json = File.ReadAllText(ConfigPath);
                var cfg = JsonSerializer.Deserialize<AppConfig>(json);
                if (cfg != null)
                {
                    cfg.SaveDir = Environment.ExpandEnvironmentVariables(cfg.SaveDir);
                    bool needSave = string.IsNullOrEmpty(cfg.DeviceId);
                    if (needSave)
                    {
                        cfg.DeviceId = Guid.NewGuid().ToString();
                        cfg.Save();
                    }
                }
                return cfg ?? new AppConfig();
            }
            catch (Exception ex)
            {
                Util.Log.Error("Failed to load config, using defaults", ex);
                return new AppConfig();
            }
        }

        public void Save()
        {
            lock (LockObj)
            {
                try
                {
                    string json = JsonSerializer.Serialize(this, new JsonSerializerOptions { WriteIndented = true });
                    File.WriteAllText(ConfigPath, json);
                }
                catch (Exception ex)
                {
                    Util.Log.Error("Failed to save config", ex);
                }
            }
        }

        public void ApplyRunAtStartup()
        {
            try
            {
                using var key = Registry.CurrentUser.OpenSubKey(
                    @"Software\Microsoft\Windows\CurrentVersion\Run", true);
                if (key == null) return;

                string appPath = Assembly.GetExecutingAssembly().Location;
                if (RunAtStartup)
                {
                    key.SetValue("ShareToPcReciver", $"\"{appPath}\"");
                }
                else
                {
                    key.DeleteValue("ShareToPcReciver", false);
                }
            }
            catch (Exception ex)
            {
                Util.Log.Error("Failed to set run-at-startup", ex);
            }
        }
    }
}