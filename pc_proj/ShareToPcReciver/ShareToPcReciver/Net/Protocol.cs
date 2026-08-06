using ShareToPcReciver.Storage;
using System;
using System.Collections.Generic;
using System.IO;
using System.Net;
using System.Reflection;
using System.Text;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using static ShareToPcReciver.Net.TransferServer;

namespace ShareToPcReciver.Net
{
    public static class Protocol
    {
        public const int HeaderMaxLength = 64 * 1024;
        public const int BufferSize = 64 * 1024;
        public const int DefaultPort = 51234;
        public const string ServiceType = "_mytransfer._tcp";
        public const string UdpBroadcastPort = "51235";
        public const int ProtocolVersion = 1;
        public static readonly string AppVersion = Assembly.GetExecutingAssembly().GetName().Name + "/" + Assembly.GetExecutingAssembly().GetName().Version.Major + "." + Assembly.GetExecutingAssembly().GetName().Version.Minor;

        public static async Task<(JsonElement header, long payloadLength,String type)> ReadFrameAsync(Stream stream, CancellationToken ct)
        {
            byte[] lenBuf = new byte[4];
            int read = 0;
            while (read < 4)
            {
                int n = await stream.ReadAsync(lenBuf, read, 4 - read, ct);
                if (n == 0) throw new IOException("Connection closed while reading frame header");
                read += n;
            }
            int headerLen = BitConverter.ToInt32(lenBuf, 0);
            if (BitConverter.IsLittleEndian)
                headerLen = IPAddress.NetworkToHostOrder(headerLen);
            if (headerLen < 0 || headerLen > HeaderMaxLength)
                throw new ProtocolViolationException($"Header length {headerLen} exceeds maximum {HeaderMaxLength}");

            byte[] headerBuf = new byte[headerLen];
            read = 0;
            while (read < headerLen)
            {
                int n = await stream.ReadAsync(headerBuf, read, headerLen - read, ct);
                if (n == 0) throw new IOException("Connection closed while reading frame header");
                read += n;
            }
            string headerJson = Encoding.UTF8.GetString(headerBuf, 0, headerLen);

            var header = JsonSerializer.Deserialize<JsonElement>(headerJson);
            string type = header.GetProperty("type").GetString() ?? "";
            long payloadLength = 0;
            if (header.TryGetProperty("size", out JsonElement sizeProp))
                payloadLength = sizeProp.GetInt64();

            return (header, payloadLength,type);
        }

        public static async Task WriteBeatAsync(Stream stream, CancellationToken ct)
        {
            var beat = new { type = "BEAT" };
            string json = JsonSerializer.Serialize(beat);
            byte[] jsonBytes = Encoding.UTF8.GetBytes(json);
            byte[] lenBytes = BitConverter.GetBytes(IPAddress.HostToNetworkOrder(jsonBytes.Length));
            await stream.WriteAsync(lenBytes, 0, 4, ct);
            await stream.WriteAsync(jsonBytes, 0, jsonBytes.Length, ct);
            await stream.FlushAsync(ct);
        }
        public static async Task WriteSerializedMessageAsync(Stream stream,String json, CancellationToken ct)
        {
            byte[] jsonBytes = Encoding.UTF8.GetBytes(json);
            byte[] lenBytes = BitConverter.GetBytes(IPAddress.HostToNetworkOrder(jsonBytes.Length));
            await stream.WriteAsync(lenBytes, 0, 4, ct);
            await stream.WriteAsync(jsonBytes, 0, jsonBytes.Length, ct);
            await stream.FlushAsync(ct);
        }

        public static async Task WriteOfflineAsync(Stream stream, CancellationToken ct)
        {
            var offline = new { type = "OFFLINE" };
            string json = JsonSerializer.Serialize(offline);
            byte[] jsonBytes = Encoding.UTF8.GetBytes(json);
            byte[] lenBytes = BitConverter.GetBytes(IPAddress.HostToNetworkOrder(jsonBytes.Length));
            await stream.WriteAsync(lenBytes, 0, 4, ct);
            await stream.WriteAsync(jsonBytes, 0, jsonBytes.Length, ct);
            await stream.FlushAsync(ct);
        }

        public static async Task WriteBadProtocolAckAsync(Stream stream, CancellationToken ct)
        {
            await WriteAckAsync(stream, false, "BAD_PROTOCOL", ct);
        }

        public static async Task WriteAckAsync(Stream stream, bool ok, string msg, CancellationToken ct)
        {
            var ack = new { type = "ACK", status = ok ? "OK" : "ERROR", msg = msg ?? "" };
            string json = JsonSerializer.Serialize(ack);
            byte[] jsonBytes = Encoding.UTF8.GetBytes(json);
            byte[] lenBytes = BitConverter.GetBytes(IPAddress.HostToNetworkOrder(jsonBytes.Length));
            await stream.WriteAsync(lenBytes, 0, 4, ct);
            await stream.WriteAsync(jsonBytes, 0, jsonBytes.Length, ct);
            await stream.FlushAsync(ct);
        }

        /// <summary>HELLO 握手 ACK，携带 PC 端程序版本、协议版本、设备 ID、心跳间隔、登录用户名、真实用户目录及磁盘列表。</summary>
        public static async Task WriteHelloAckAsync(Stream stream, string deviceId, int hbIntervalMs,
            Dictionary<string, string> userFolders, List<Drives.DriveData> drives, CancellationToken ct)
        {
            var ack = new { type = "ACK", status = "OK", msg = "", version = AppVersion, protoVersion = ProtocolVersion,
                deviceId, hbInterval = hbIntervalMs, userName = Environment.UserName,
                machineName = Environment.MachineName,
                userFolders = userFolders ?? new Dictionary<string, string>(),
                drives = drives ?? new List<Drives.DriveData>() }; 
            string json = JsonSerializer.Serialize(ack);
            byte[] jsonBytes = Encoding.UTF8.GetBytes(json);
            byte[] lenBytes = BitConverter.GetBytes(IPAddress.HostToNetworkOrder(jsonBytes.Length));
            await stream.WriteAsync(lenBytes, 0, 4, ct);
            await stream.WriteAsync(jsonBytes, 0, jsonBytes.Length, ct);
            await stream.FlushAsync(ct);
        }

        /// <summary>发送断点续传响应，告知安卓端从 offset 位置继续传输。</summary>
        public static async Task WriteResumeAckAsync(Stream stream, long offset, CancellationToken ct)
        {
            var ack = new { type = "ACK", status = "RESUME", msg = "", offset };
            string json = JsonSerializer.Serialize(ack);
            byte[] jsonBytes = Encoding.UTF8.GetBytes(json);
            byte[] lenBytes = BitConverter.GetBytes(IPAddress.HostToNetworkOrder(jsonBytes.Length));
            await stream.WriteAsync(lenBytes, 0, 4, ct);
            await stream.WriteAsync(jsonBytes, 0, jsonBytes.Length, ct);
            await stream.FlushAsync(ct);
        }
        public static async Task WriteJobAckAsync(Stream stream,AuthResult session, CancellationToken ct)
        { 
            await WriteJobAckAsync(stream, session.Status, session.PairKey, session.Credits, session.MaxItemsPerJob, session.MaxBytesPerJob, false, ct);
        }
        /// <summary>发送 JOB_ACK 响应，返回授权结果及约束。</summary>
        public static async Task WriteJobAckAsync(Stream stream, string status, string pairKey,
            int credits, int maxItemsPerJob, long maxBytesPerJob, bool reciverCanceled, CancellationToken ct)
        {
            var ack = new { type = "JOB_ACK", status, pairKey = pairKey ?? "", credits, maxItemsPerJob, maxBytesPerJob, stopRetry=reciverCanceled };
            string json = JsonSerializer.Serialize(ack);
            byte[] jsonBytes = Encoding.UTF8.GetBytes(json);
            byte[] lenBytes = BitConverter.GetBytes(IPAddress.HostToNetworkOrder(jsonBytes.Length));
            await stream.WriteAsync(lenBytes, 0, 4, ct);
            await stream.WriteAsync(jsonBytes, 0, jsonBytes.Length, ct);
            await stream.FlushAsync(ct);
        }

        public static async Task WriteStillAckAsync(Stream stream, long elapsedMillis,long token, CancellationToken ct)
        {
            var ack = new { type = "JOB_ACK", status = "STILL", msg = "", elapsed= elapsedMillis, token=token };
            string json = JsonSerializer.Serialize(ack);
            byte[] jsonBytes = Encoding.UTF8.GetBytes(json);
            byte[] lenBytes = BitConverter.GetBytes(IPAddress.HostToNetworkOrder(jsonBytes.Length));
            await stream.WriteAsync(lenBytes, 0, 4, ct);
            await stream.WriteAsync(jsonBytes, 0, jsonBytes.Length, ct);
            await stream.FlushAsync(ct);
        }

        public static byte[] WriteFrame(string type, string jsonPayload, byte[] filePayload)
        {
            byte[] typeBytes = Encoding.UTF8.GetBytes(type);
            byte[] headerBytes = Encoding.UTF8.GetBytes(jsonPayload);
            int headerLen = headerBytes.Length;
            byte[] headerLenBytes = BitConverter.GetBytes(IPAddress.HostToNetworkOrder(headerLen));
            using var ms = new MemoryStream();
            ms.Write(headerLenBytes, 0, 4);
            ms.Write(headerBytes, 0, headerLen);
            if (filePayload != null && filePayload.Length > 0)
                ms.Write(filePayload, 0, filePayload.Length);
            return ms.ToArray();
        }

        public static byte[] ReadPayload(Stream stream, int length, CancellationToken ct)
        {
            byte[] buf = new byte[length];
            int read = 0;
            while (read < length)
            {
                int n = stream.Read(buf, read, length - read);
                if (n == 0) throw new IOException("Connection closed while reading payload");
                read += n;
            }
            return buf;
        }

        public static async Task ReadPayloadAsync(Stream stream, long length, Stream output, CancellationToken ct, Action<long> progress = null)
        {
            byte[] buf = new byte[BufferSize];
            long remaining = length;
            while (remaining > 0)
            {
                int toRead = (int)Math.Min(buf.Length, remaining);
                int n = await stream.ReadAsync(buf, 0, toRead, ct);
                if (n == 0) throw new IOException("Connection closed while reading payload");
                await output.WriteAsync(buf, 0, n, ct);
                remaining -= n;
                progress?.Invoke(length - remaining);
            }
        }
    }
}