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

        public static AppConfig Load()
        {
            if (!File.Exists(ConfigPath))
                return new AppConfig();

            try
            {
                string json = File.ReadAllText(ConfigPath);
                var cfg = JsonSerializer.Deserialize<AppConfig>(json);
                if (cfg != null)
                {
                    cfg.SaveDir = Environment.ExpandEnvironmentVariables(cfg.SaveDir);
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