using System;
using System.Collections.Generic;
using System.Text.Json.Serialization;

namespace ShareToPcReciver.Net
{
    /// <summary>电脑磁盘列表，随 HELLO ACK 上报给手机端（真实存在的盘 + 类型）。</summary>
    public static class Drives
    {
        public class DriveData
        {
            /// <summary>盘符，如 "C:"（无尾随反斜杠）。</summary>
            [JsonPropertyName("name")] public string Name { get; set; }
            /// <summary>磁盘类型：Fixed / Removable / Network / CDRom / Ram / Unknown。</summary>
            [JsonPropertyName("type")] public string Type { get; set; }
            /// <summary>卷标，可为空。</summary>
            [JsonPropertyName("label")] public string Label { get; set; }
            /// <summary>文件系统，如 NTFS / FAT32 / exFAT，可为空。</summary>
            [JsonPropertyName("format")] public string Format { get; set; }
        }

        /// <summary>枚举所有逻辑驱动器；卷标/文件系统读取失败（如未就绪）时置空。</summary>
        public static List<DriveData> GetAll()
        {
            var list = new List<DriveData>();
            foreach (var d in System.IO.DriveInfo.GetDrives())
            {
                string label = "";
                string format = "";
                try { label = d.VolumeLabel ?? ""; } catch { }
                try { format = d.DriveFormat ?? ""; } catch { }
                list.Add(new DriveData
                {
                    Name = d.Name.TrimEnd('\\'),
                    Type = d.DriveType.ToString(),
                    Label = label,
                    Format = format
                });
            }
            return list;
        }
    }
}
