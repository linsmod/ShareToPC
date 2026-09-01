using System;
using System.Collections.Generic;
using System.Net.NetworkInformation;
using System.Net.Sockets;

namespace ShareToPcReciver.Net
{
    /// <summary>一个可用的局域网 IP 及所属网卡。</summary>
    public class LocalIpCandidate
    {
        public string Ip { get; }
        public string AdapterName { get; }
        public bool HasGateway { get; }

        public LocalIpCandidate(string ip, string adapterName, bool hasGateway)
        {
            Ip = ip;
            AdapterName = adapterName;
            HasGateway = hasGateway;
        }
    }

    /// <summary>枚举本机可用的局域网 IP，排除虚拟机 / 隧道等虚拟网卡。</summary>
    public static class LocalNetwork
    {
        private static readonly string[] VirtualAdapterKeywords =
        {
            "vmware", "virtualbox", "virtual", "vethernet", "hyper-v",
            "hyperv", "tap", "tun", "wintun", "wireguard", "openvpn"
        };

        private static readonly byte[][] VirtualMacPrefixes =
        {
            new byte[] { 0x00, 0x0C, 0x29 },  // VMware
            new byte[] { 0x00, 0x05, 0x69 },  // VMware
            new byte[] { 0x00, 0x50, 0x56 },  // VMware
            new byte[] { 0x00, 0x1C, 0x14 },  // VMware
            new byte[] { 0x08, 0x00, 0x27 },  // VirtualBox
            new byte[] { 0x00, 0x15, 0x5D },  // Hyper-V
            new byte[] { 0x00, 0x16, 0x3E },  // Xen
        };

        public static bool IsVirtualAdapter(NetworkInterface ni)
        {
            string name = (ni.Name + " " + ni.Description).ToLowerInvariant();
            foreach (var kw in VirtualAdapterKeywords)
                if (name.Contains(kw)) return true;

            byte[] mac = ni.GetPhysicalAddress().GetAddressBytes();
            if (mac.Length >= 3)
            {
                foreach (var prefix in VirtualMacPrefixes)
                {
                    if (mac[0] == prefix[0] && mac[1] == prefix[1] && mac[2] == prefix[2])
                        return true;
                }
            }
            return false;
        }

        /// <summary>返回所有候选 IP，优先（带默认网关的）真实网卡在前。</summary>
        public static List<LocalIpCandidate> GetCandidates()
        {
            var candidates = new List<LocalIpCandidate>();
            try
            {
                foreach (var ni in NetworkInterface.GetAllNetworkInterfaces())
                {
                    if (ni.OperationalStatus != OperationalStatus.Up) continue;
                    var type = ni.NetworkInterfaceType;
                    if (type == NetworkInterfaceType.Loopback || type == NetworkInterfaceType.Tunnel) continue;
                    if (IsVirtualAdapter(ni)) continue;

                    var props = ni.GetIPProperties();
                    bool hasGateway = props.GatewayAddresses.Count > 0;
                    foreach (var uni in props.UnicastAddresses)
                    {
                        if (uni.Address.AddressFamily != AddressFamily.InterNetwork) continue;
                        if (uni.Address.ToString().StartsWith("127.")) continue;
                        candidates.Add(new LocalIpCandidate(uni.Address.ToString(), ni.Description, hasGateway));
                    }
                }
            }
            catch (Exception ex)
            {
                Util.Log.Warn($"Failed to enumerate network interfaces: {ex.Message}");
            }

            candidates.Sort((a, b) =>
            {
                int g = b.HasGateway.CompareTo(a.HasGateway);
                return g != 0 ? g : string.CompareOrdinal(a.Ip, b.Ip);
            });
            return candidates;
        }

        /// <summary>取主 IP：优先带网关的候选，其次第一个候选，最后回退回环地址。</summary>
        public static string GetPrimaryIp()
        {
            var candidates = GetCandidates();
            foreach (var c in candidates)
                if (c.HasGateway) return c.Ip;
            return candidates.Count > 0 ? candidates[0].Ip : "127.0.0.1";
        }
    }
}
