using System;

namespace ShareToPcReciver.Models
{
    public class ReceivedText
    {
        public string DeviceName { get; set; } = "";
        public string Text { get; set; } = "";
        public long Time { get; set; }
        public string JobId { get; set; } = "";
        public string FilePath { get; set; } = "";
        public DateTime ReceivedAt => DateTimeOffset.FromUnixTimeMilliseconds(Time).LocalDateTime;
    }
}