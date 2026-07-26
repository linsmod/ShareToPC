using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text.Json;
using ShareToPcReciver.Models;

namespace ShareToPcReciver.Storage
{
    /// <summary>
    /// 接收历史的持久化存储：JSON 文件保存在 %LocalAppData%\ShareToPcReciver\history.json。
    /// 文本和文件记录统一序列化为带 kind 标签的条目。
    /// </summary>
    public class HistoryStore
    {
        private const int MaxEntries = 500;

        private static readonly string StorePath = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "ShareToPcReciver", "history.json");

        private readonly object _lock = new object();

        private class Entry
        {
            public string Kind { get; set; } = ""; // "text" | "file"
            public string DeviceName { get; set; } = "";
            public long Time { get; set; }
            // text
            public string Text { get; set; }
            // file
            public string FileName { get; set; }
            public string FilePath { get; set; }
            public long Size { get; set; }
        }

        public List<object> Load()
        {
            lock (_lock)
            {
                try
                {
                    if (!File.Exists(StorePath))
                        return new List<object>();
                    string json = File.ReadAllText(StorePath);
                    var entries = JsonSerializer.Deserialize<List<Entry>>(json) ?? new List<Entry>();
                    var result = new List<object>(entries.Count);
                    foreach (var e in entries)
                    {
                        if (e.Kind == "text")
                        {
                            result.Add(new ReceivedText
                            {
                                DeviceName = e.DeviceName ?? "",
                                Text = e.Text ?? "",
                                Time = e.Time
                            });
                        }
                        else if (e.Kind == "file")
                        {
                            result.Add(new ReceivedFile
                            {
                                DeviceName = e.DeviceName ?? "",
                                FileName = e.FileName ?? "",
                                FilePath = e.FilePath ?? "",
                                Size = e.Size,
                                Time = e.Time,
                                Progress = 100
                            });
                        }
                    }
                    return result;
                }
                catch (Exception ex)
                {
                    Util.Log.Error("Failed to load history", ex);
                    return new List<object>();
                }
            }
        }

        public void Save(IEnumerable<object> history)
        {
            lock (_lock)
            {
                try
                {
                    var entries = history.Select(ToEntry).Where(e => e != null).ToList();
                    string json = JsonSerializer.Serialize(entries, new JsonSerializerOptions { WriteIndented = false });
                    Directory.CreateDirectory(Path.GetDirectoryName(StorePath));
                    string tmp = StorePath + ".tmp";
                    File.WriteAllText(tmp, json);
                    if (File.Exists(StorePath))
                        File.Delete(StorePath);
                    File.Move(tmp, StorePath);
                }
                catch (Exception ex)
                {
                    Util.Log.Error("Failed to save history", ex);
                }
            }
        }

        private static Entry ToEntry(object item)
        {
            switch (item)
            {
                case ReceivedText t:
                    return new Entry
                    {
                        Kind = "text",
                        DeviceName = t.DeviceName,
                        Time = t.Time,
                        Text = t.Text
                    };
                case ReceivedFile f:
                    return new Entry
                    {
                        Kind = "file",
                        DeviceName = f.DeviceName,
                        Time = f.Time,
                        FileName = f.FileName,
                        FilePath = f.FilePath,
                        Size = f.Size
                    };
                default:
                    return null;
            }
        }
    }
}
