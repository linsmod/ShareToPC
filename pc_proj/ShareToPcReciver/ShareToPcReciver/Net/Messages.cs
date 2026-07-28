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
}