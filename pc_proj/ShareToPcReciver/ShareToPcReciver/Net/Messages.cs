using System;
using System.Text.Json.Serialization;

namespace ShareToPcReciver.Net
{
    public class HelloMsg
    {
        [JsonPropertyName("type")]
        public string Type => "HELLO";

        [JsonPropertyName("device")]
        public string Device { get; set; } = "";

        [JsonPropertyName("version")]
        public string Version { get; set; } = "";

        [JsonPropertyName("protoVersion")]
        public int ProtoVersion { get; set; }

        [JsonPropertyName("deviceId")]
        public string DeviceId { get; set; } = "";
    }

    public class TextMsg
    {
        [JsonPropertyName("type")]
        public string Type => "TEXT";

        [JsonPropertyName("text")]
        public string Text { get; set; } = "";

        [JsonPropertyName("time")]
        public long Time { get; set; }
    }

    public class FileMsg
    {
        [JsonPropertyName("type")]
        public string Type => "FILE";

        [JsonPropertyName("name")]
        public string Name { get; set; } = "";

        [JsonPropertyName("size")]
        public long Size { get; set; }

        [JsonPropertyName("mime")]
        public string Mime { get; set; } = "";

        [JsonPropertyName("time")]
        public long Time { get; set; }
    }

    public class AckMsg
    {
        [JsonPropertyName("type")]
        public string Type => "ACK";

        [JsonPropertyName("status")]
        public string Status { get; set; } = "";

        [JsonPropertyName("msg")]
        public string Msg { get; set; } = "";
    }

    public class PingMsg
    {
        [JsonPropertyName("type")]
        public string Type => "PING";
    }

    public class PongMsg
    {
        [JsonPropertyName("type")]
        public string Type => "PONG";
    }

    public class CancelMsg
    {
        [JsonPropertyName("type")]
        public string Type => "CANCEL";
    }

    public class JobInfoMsg
    {
        [JsonPropertyName("type")]
        public string Type => "JOB_INFO";

        [JsonPropertyName("deviceId")]
        public string DeviceId { get; set; } = "";

        [JsonPropertyName("pairKey")]
        public string PairKey { get; set; } = "";

        [JsonPropertyName("jobId")]
        public string JobId { get; set; } = "";

        [JsonPropertyName("itemCount")]
        public int ItemCount { get; set; }

        [JsonPropertyName("totalBytes")]
        public long TotalBytes { get; set; }
    }

    public class JobAckMsg
    {
        [JsonPropertyName("type")]
        public string Type => "JOB_ACK";

        [JsonPropertyName("status")]
        public string Status { get; set; } = "";

        [JsonPropertyName("pairKey")]
        public string PairKey { get; set; } = "";

        [JsonPropertyName("credits")]
        public int Credits { get; set; }

        [JsonPropertyName("maxItemsPerJob")]
        public int MaxItemsPerJob { get; set; }

        [JsonPropertyName("maxBytesPerJob")]
        public long MaxBytesPerJob { get; set; }
    }
}