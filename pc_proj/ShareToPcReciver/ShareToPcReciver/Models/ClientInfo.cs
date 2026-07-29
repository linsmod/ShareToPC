using System;

namespace ShareToPcReciver.Models
{
    public class ClientInfo
    {
        public string Name { get; set; } = "";
        public string Address { get; set; } = "";
        public string DeviceId { get; set; } = "";
        public string Version { get; set; } = "";
        public int ProtoVersion { get; set; }
        public DateTime LastSeen { get; set; } = DateTime.UtcNow;
    }
}