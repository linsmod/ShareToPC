using Makaretu.Dns;
using System;

namespace ShareToPcReciver.Net
{
    public class ServiceAdvertiser : IDisposable
    {
        private ServiceDiscovery _sd;
        private ServiceProfile _profile;

        public bool IsActive => _sd != null;

        public void Start(int port)
        {
            Stop();
            try
            {
                _sd = new ServiceDiscovery();
                _profile = new ServiceProfile(
                    instanceName: Environment.MachineName,
                    serviceName: Protocol.ServiceType,
                    port: (ushort)port);
                _profile.AddProperty("version", "1");
                _sd.Advertise(_profile);
                Util.Log.Info($"mDNS advertised: {Protocol.ServiceType} on port {port}");
            }
            catch (Exception ex)
            {
                Util.Log.Error("mDNS advertisement failed", ex);
                _sd = null;
            }
        }

        public void Stop()
        {
            if (_sd != null)
            {
                try { _sd.Dispose(); } catch { }
                _sd = null;
                _profile = null;
                Util.Log.Info("mDNS advertisement stopped");
            }
        }

        public void Dispose()
        {
            Stop();
        }
    }
}