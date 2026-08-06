package github.linsmod.sharetopc.transfer;

/**
 * PC 端 HELLO 握手上报的磁盘项（DriveInfo.GetDrives）。
 */
public class RemoteDrive {

    /** 盘符，如 "C:"。 */
    public final String name;
    /** 磁盘类型：Fixed / Removable / Network / CDRom / Ram / Unknown。 */
    public final String type;
    /** 卷标，可为空。 */
    public final String label;
    /** 文件系统，如 NTFS / FAT32 / exFAT，可为空。 */
    public final String format;

    public RemoteDrive(String name, String type, String label, String format) {
        this.name = name != null ? name : "";
        this.type = type != null ? type : "";
        this.label = label != null ? label : "";
        this.format = format != null ? format : "";
    }
}
