using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text.Json;
using ShareToPcReciver.Models;

namespace ShareToPcReciver.Storage
{
    /// <summary>
    /// 已连接设备信息持久化：JSON 文件保存在 %LocalAppData%\ShareToPcReciver\devices.json。
    /// 存储 Android 设备通过 HELLO 握手发来的设备名、版本、协议版本、deviceId 等信息。
    /// </summary>
    public class DeviceStore
    {
        private static readonly string StorePath = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "ShareToPcReciver", "devices.json");

        private readonly object _lock = new object();

        /// <summary>加载所有已知设备。</summary>
        public List<ClientInfo> LoadAll()
        {
            lock (_lock)
            {
                try
                {
                    if (!File.Exists(StorePath))
                        return new List<ClientInfo>();
                    string json = File.ReadAllText(StorePath);
                    return JsonSerializer.Deserialize<List<ClientInfo>>(json) ?? new List<ClientInfo>();
                }
                catch (Exception ex)
                {
                    Util.Log.Error("Failed to load devices", ex);
                    return new List<ClientInfo>();
                }
            }
        }

        /// <summary>以 Address 为 key，保存或更新设备信息。</summary>
        public void SaveDevice(ClientInfo info)
        {
            lock (_lock)
            {
                try
                {
                    var devices = LoadAll();
                    var existing = devices.FirstOrDefault(d => d.Address == info.Address);
                    if (existing != null)
                    {
                        existing.Name = info.Name;
                        existing.DeviceId = info.DeviceId;
                        existing.Version = info.Version;
                        existing.ProtoVersion = info.ProtoVersion;
                        existing.LastSeen = info.LastSeen;
                    }
                    else
                    {
                        devices.Add(info);
                    }
                    string json = JsonSerializer.Serialize(devices, new JsonSerializerOptions { WriteIndented = true });
                    Directory.CreateDirectory(Path.GetDirectoryName(StorePath));
                    string tmp = StorePath + ".tmp";
                    File.WriteAllText(tmp, json);
                    if (File.Exists(StorePath))
                        File.Delete(StorePath);
                    File.Move(tmp, StorePath);
                }
                catch (Exception ex)
                {
                    Util.Log.Error("Failed to save device", ex);
                }
            }
        }
    }
}
