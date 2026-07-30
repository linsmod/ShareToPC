using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using System.Threading.Tasks;

namespace ShareToPcReciver.Net
{
    internal class AuthInfo
    {
        public string Summary { get; internal set; }
        public string DeviceId { get; internal set; }
        public object DeviceName { get; internal set; }
        public long TotalBytes { get; internal set; }
        public int ItemCount { get; internal set; }
        public string PairKey { get; internal set; }
        public string JobId { get; internal set; }

        public AuthInfo() {

            string sizeText = TotalBytes >= 1024 * 1024
                ? $"{TotalBytes / (1024.0 * 1024.0):F1} MB"
                : TotalBytes >= 1024
                    ? $"{TotalBytes / 1024.0:F1} KB"
                    : $"{TotalBytes} B";

            string msg = $"\"{DeviceName}\" 请求向你发送文件：\n\n"
                + $"共 {ItemCount} 个文件，合计 {sizeText}\n\n";
        }
    }
}
