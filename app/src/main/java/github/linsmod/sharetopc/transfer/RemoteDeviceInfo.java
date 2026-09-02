package github.linsmod.sharetopc.transfer;

import java.util.List;
import java.util.Map;

public class RemoteDeviceInfo{

    public String clientVersion;
    public int protoVer;
    public String deviceId;
    public String deviceName;
    /** PC 端 HELLO 握手上报的当前登录用户名（Windows %USERNAME%）。 */
    public String userName;
    /** PC 端 HELLO 握手上报的计算机名（Environment.MachineName）。 */
    public String machineName;
    /** PC 端 HELLO 握手上报的真实用户目录（desktop/documents/... → 路径，SHGetKnownFolderPath）。 */
    public Map<String, String> userFolders;
    /** PC 端 HELLO 握手上报的磁盘列表（DriveInfo.GetDrives）。 */
    public List<RemoteDrive> drives;
    /** Exposed Etp port by PC side program*/
    public int etpPort;
}
