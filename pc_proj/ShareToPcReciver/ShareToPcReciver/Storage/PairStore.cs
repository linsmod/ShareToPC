using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text.Json;
using ShareToPcReciver.Config;

namespace ShareToPcReciver.Storage
{
    /// <summary>
    /// 设备配对授权信息持久化存储。
    /// 保存在 %LocalAppData%\ShareToPcReciver\pair_store.json。
    /// </summary>
    public class PairStore
    {
        private static readonly string StorePath = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "ShareToPcReciver", "pair_store.json");

        private static readonly string BlocklistPath = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "ShareToPcReciver", "blocked_devices.json");

        private readonly object _lock = new object();

        public class PairSession
        {
            public string DeviceId { get; set; } = "";
            public string DeviceName { get; set; } = "";
            public string Key { get; set; } = "";
            public int Credits { get; set; }
            public int MaxItemsPerJob { get; set; }
            public long MaxBytesPerJob { get; set; }
            public long IssuedAt { get; set; }
            /// <summary>过期时间（Unix 毫秒），0 表示永不过期。</summary>
            public long ExpiresAt { get; set; }
            /// <summary>收到文本时的动作，null 表示跟随全局设定。</summary>
            public ArrivalAction? TextAction { get; set; }
        }

        /// <summary>检查授权是否已过期（已过期返回 true）。</summary>
        public bool IsExpired(PairSession session)
        {
            if (session.ExpiresAt <= 0)
                return false;
            return DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() > session.ExpiresAt;
        }

        public bool IsOutofCredits(PairSession session)
        {
            return session.Credits <= 0;
        }

        public bool IsItemCountExceeded(PairSession session, int itemCount)
        {
            return itemCount > session.MaxItemsPerJob;
        }

        public bool IsByteCountExceeded(PairSession session, long byteCount)
        {
            return byteCount > session.MaxBytesPerJob;
        }

        public List<PairSession> LoadAll()
        {
            lock (_lock)
            {
                try
                {
                    if (!File.Exists(StorePath))
                        return new List<PairSession>();
                    string json = File.ReadAllText(StorePath);
                    return JsonSerializer.Deserialize<List<PairSession>>(json) ?? new List<PairSession>();
                }
                catch (Exception ex)
                {
                    Util.Log.Error("Failed to load pair store", ex);
                    return new List<PairSession>();
                }
            }
        }

        /// <summary>根据 pairKey 查找，未找到返回 null。</summary>
        public PairSession GetByKey(string pairKey)
        {
            if (string.IsNullOrEmpty(pairKey)) return null;
            return LoadAll().FirstOrDefault(s => s.Key == pairKey);
        }

        /// <summary>根据 deviceId 查找，未找到返回 null。</summary>
        public PairSession GetByDeviceId(string deviceId)
        {
            if (string.IsNullOrEmpty(deviceId)) return null;
            return LoadAll().FirstOrDefault(s => s.DeviceId == deviceId);
        }

        /// <summary>保存或更新配对记录（按 deviceId 去重）。</summary>
        public void Save(PairSession session)
        {
            lock (_lock)
            {
                try
                {
                    var list = LoadAll();
                    var existing = list.FirstOrDefault(s => s.DeviceId == session.DeviceId);
                    if (existing != null)
                    {
                        existing.Key = session.Key;
                        existing.Credits = session.Credits;
                        existing.MaxItemsPerJob = session.MaxItemsPerJob;
                        existing.MaxBytesPerJob = session.MaxBytesPerJob;
                        existing.IssuedAt = session.IssuedAt;
                        existing.DeviceName = session.DeviceName;
                        existing.ExpiresAt = session.ExpiresAt;
                        existing.TextAction = session.TextAction;
                    }
                    else
                    {
                        list.Add(session);
                    }

                    string json = JsonSerializer.Serialize(list, new JsonSerializerOptions { WriteIndented = true });
                    Directory.CreateDirectory(Path.GetDirectoryName(StorePath));
                    string tmp = StorePath + ".tmp";
                    File.WriteAllText(tmp, json);
                    if (File.Exists(StorePath))
                        File.Delete(StorePath);
                    File.Move(tmp, StorePath);
                }
                catch (Exception ex)
                {
                    Util.Log.Error("Failed to save pair store", ex);
                }
            }
        }

        /// <summary>检查设备是否被拉黑。</summary>
        public bool IsBlocked(string deviceId)
        {
            if (string.IsNullOrEmpty(deviceId)) return false;
            return LoadBlocklist().Contains(deviceId);
        }

        /// <summary>拉黑设备。</summary>
        public void BlockDevice(string deviceId)
        {
            if (string.IsNullOrEmpty(deviceId)) return;
            lock (_lock)
            {
                var list = LoadBlocklist();
                if (!list.Contains(deviceId))
                {
                    list.Add(deviceId);
                    SaveBlocklist(list);
                }
            }
        }

        /// <summary>取消拉黑设备。</summary>
        public void UnblockDevice(string deviceId)
        {
            if (string.IsNullOrEmpty(deviceId)) return;
            lock (_lock)
            {
                var list = LoadBlocklist();
                if (list.Remove(deviceId))
                    SaveBlocklist(list);
            }
        }

        private HashSet<string> LoadBlocklist()
        {
            try
            {
                if (!File.Exists(BlocklistPath))
                    return new HashSet<string>();
                string json = File.ReadAllText(BlocklistPath);
                return JsonSerializer.Deserialize<HashSet<string>>(json) ?? new HashSet<string>();
            }
            catch
            {
                return new HashSet<string>();
            }
        }

        private void SaveBlocklist(HashSet<string> list)
        {
            try
            {
                string json = JsonSerializer.Serialize(list, new JsonSerializerOptions { WriteIndented = true });
                Directory.CreateDirectory(Path.GetDirectoryName(BlocklistPath));
                File.WriteAllText(BlocklistPath, json);
            }
            catch (Exception ex)
            {
                Util.Log.Error("Failed to save blocklist", ex);
            }
        }

        /// <summary>删除指定 deviceId 的配对记录。</summary>
        public void Delete(string deviceId)
        {
            lock (_lock)
            {
                try
                {
                    var list = LoadAll();
                    list.RemoveAll(s => s.DeviceId == deviceId);
                    string json = JsonSerializer.Serialize(list, new JsonSerializerOptions { WriteIndented = true });
                    Directory.CreateDirectory(Path.GetDirectoryName(StorePath));
                    File.WriteAllText(StorePath, json);
                }
                catch (Exception ex)
                {
                    Util.Log.Error("Failed to delete from pair store", ex);
                }
            }
        }

        internal void BlockDevice(object deviceId)
        {
            throw new NotImplementedException();
        }
    }
}
