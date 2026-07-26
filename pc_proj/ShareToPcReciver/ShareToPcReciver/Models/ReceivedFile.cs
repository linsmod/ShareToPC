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
        public DateTime ReceivedAt => DateTimeOffset.FromUnixTimeMilliseconds(Time).LocalDateTime;
    }

    public class FileProgressInfo
    {
        public string FileName { get; set; } = "";
        public string DeviceName { get; set; } = "";
        public long Received { get; set; }
        public long Total { get; set; }
        public bool Failed { get; set; }
        public bool Cancelled { get; set; }
        public int Percent => Total > 0 ? (int)(Received * 100 / Total) : 0;
    }
}