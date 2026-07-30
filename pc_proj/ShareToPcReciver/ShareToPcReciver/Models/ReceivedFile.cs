using System;

namespace ShareToPcReciver.Models
{
    public class ReceivedFile
    {
        public string FileName { get; set; } = "";
        public string FilePath { get; set; } = "";
        public long Size { get; set; }
        public string DeviceName { get; set; } = "";
        public long Time { get; set; }
        public int Progress { get; set; }
        public string Status { get; set; } = "success";  // success | failed | cancelled
        public long TotalBytes { get; set; }               // 文件总大小
        public long ReceivedBytes { get; set; }            // 已接收字节数（断点续传用）
        public string TransferId { get; set; } = "";       // 传输标识，用于定位 .part 文件
        public string JobId { get; set; } = "";            // Android 端发送批次标识
        public DateTime ReceivedAt => DateTimeOffset.FromUnixTimeMilliseconds(Time).LocalDateTime;

        public int ObjectType { get; internal set; }
    }

    public class FileProgressInfo
    {
        public string FileName { get; set; } = "";
        public string DeviceName { get; set; } = "";
        public string TransferId { get; set; } = "";
        public string JobId { get; set; } = "";
        public long Received { get; set; }
        public long Total { get; set; }
        public bool Failed { get; set; }
        public bool Cancelled { get; set; }
        public int Percent => Total > 0 ? (int)(Received * 100 / Total) : 0;

        public int ObjectType { get; internal set; }
    }
}