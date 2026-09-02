using System;
using System.Collections.Generic;
using System.Drawing;
using System.Threading.Tasks;
using System.Windows.Forms;
using ShareToPcReciver.Config;
using ShareToPcReciver.Net;
using ShareToPcReciver.Etp;
using ShareToPcReciver.Util;
using System.Threading;

namespace ShareToPcReciver
{
    public class SettingsDialog : Form
    {
        public int Port { get; private set; }
        public string SelectedIp { get; private set; }
        public string SaveDir { get; private set; }
        public ArrivalAction TextAction { get; private set; }
        public ArrivalAction FileAction { get; private set; }
        public bool ShowBalloon { get; private set; }
        public bool RunAtStartup { get; private set; }
        public bool EtpPullEnabled { get; private set; }
        public bool EtpSearchEnabled { get; private set; }
        public string PullAllowedExtensions { get; private set; }

        private readonly int _currentPort;
        private readonly string _currentIp;

        private readonly EverythingLocator _etpLocator = new EverythingLocator();

        private class ActionOption
        {
            public ArrivalAction Value { get; }
            public string Label { get; }
            public ActionOption(ArrivalAction value, string label)
            {
                Value = value;
                Label = label;
            }
        }

        private class IpOption
        {
            public string Ip { get; }
            public string Label { get; }
            public IpOption(string ip, string label)
            {
                Ip = ip;
                Label = label;
            }
        }

        private static readonly List<ActionOption> TextActionOptions = new List<ActionOption>
        {
            new ActionOption(ArrivalAction.None, "无操作"),
            new ActionOption(ArrivalAction.CopyText, "复制文本到剪贴板"),
        };
        private CheckBox chkDownload;
        private LinkLabel linkEtpStatus;
        private Label label1;
        private static readonly List<ActionOption> FileActionOptions = new List<ActionOption>
        {
            new ActionOption(ArrivalAction.None, "无操作"),
            new ActionOption(ArrivalAction.CopyFile, "复制文件到剪贴板"),
            new ActionOption(ArrivalAction.CopyFilePath, "复制文件路径到剪贴板"),
            new ActionOption(ArrivalAction.OpenFile, "打开文件"),
            new ActionOption(ArrivalAction.OpenDir, "打开所在文件夹"),
        };

        /// <summary>
        /// 无参构造函数：仅供 WinForms 设计器使用，不要在代码中直接调用
        /// </summary>
        public SettingsDialog()
        {
            InitializeComponent();
        }

        public SettingsDialog(AppConfig config, int currentPort)
        {
            _currentPort = currentPort;
            _currentIp = config.ListenIp;
            Port = currentPort;
            SelectedIp = config.ListenIp;
            SaveDir = config.SaveDir;
            TextAction = config.TextAction;
            FileAction = config.FileAction;
            ShowBalloon = config.ShowBalloon;
            RunAtStartup = config.RunAtStartup;
            EtpSearchEnabled = config.SearchEnabled;
            EtpPullEnabled = config.PullEnabled;
            PullAllowedExtensions = config.PullAllowedExtensions;
            InitializeComponent();

            // 将依赖外部数据的赋值放在 InitializeComponent 之后，避免设计器序列化错误
            txtPort.Text = Port.ToString();
            PopulateIpCombo();
            txtSaveDir.Text = SaveDir;
            chkShowBalloon.Checked = ShowBalloon;
            chkRunAtStartup.Checked = RunAtStartup;

            chkEtp.Checked = EtpSearchEnabled;
            chkDownload.Checked = EtpPullEnabled;
            chkEtp.CheckedChanged += chkEtp_CheckedChanged;
            chkDownload.CheckedChanged += chkEtpDownload_CheckedChanged;
            txtPullExt.Text = PullAllowedExtensions;

            BindActionCombo(cmbTextAction, TextActionOptions, TextAction);
            BindActionCombo(cmbFileAction, FileActionOptions, FileAction);
            BeginDetectEtp();
        }

        private static void BindActionCombo(ComboBox combo, List<ActionOption> options, ArrivalAction selected)
        {
            combo.DataSource = options;
            combo.DisplayMember = "Label";
            combo.DropDownStyle = ComboBoxStyle.DropDownList;
            int index = options.FindIndex(o => o.Value == selected);
            combo.SelectedIndex = index >= 0 ? index : 0;
        }

        private void PopulateIpCombo()
        {
            cmbIp.DropDownStyle = ComboBoxStyle.DropDownList;
            cmbIp.DisplayMember = "Label";

            int index = 0;
            int selectedIndex = 0;
            cmbIp.Items.Add(new IpOption("", "0.0.0.0 (所有网卡)"));
            foreach (var c in LocalNetwork.GetCandidates())
            {
                cmbIp.Items.Add(new IpOption(c.Ip, $"{c.Ip} ({c.AdapterName})"));
                index++;
                if (!string.IsNullOrEmpty(_currentIp) && string.Equals(c.Ip, _currentIp, StringComparison.OrdinalIgnoreCase))
                    selectedIndex = index;
            }
            cmbIp.SelectedIndex = selectedIndex;
        }

        private void InitializeComponent()
        {
            this.grpEndpoint = new System.Windows.Forms.GroupBox();
            this.lblIp = new System.Windows.Forms.Label();
            this.cmbIp = new System.Windows.Forms.ComboBox();
            this.lblPort = new System.Windows.Forms.Label();
            this.txtPort = new System.Windows.Forms.TextBox();
            this.grpReceive = new System.Windows.Forms.GroupBox();
            this.lblSaveDir = new System.Windows.Forms.Label();
            this.txtSaveDir = new System.Windows.Forms.TextBox();
            this.btnBrowse = new System.Windows.Forms.Button();
            this.lblTextAction = new System.Windows.Forms.Label();
            this.cmbTextAction = new System.Windows.Forms.ComboBox();
            this.lblFileAction = new System.Windows.Forms.Label();
            this.cmbFileAction = new System.Windows.Forms.ComboBox();
            this.chkShowBalloon = new System.Windows.Forms.CheckBox();
            this.chkRunAtStartup = new System.Windows.Forms.CheckBox();
            this.grpPull = new System.Windows.Forms.GroupBox();
            this.linkEtpStatus = new System.Windows.Forms.LinkLabel();
            this.chkDownload = new System.Windows.Forms.CheckBox();
            this.chkEtp = new System.Windows.Forms.CheckBox();
            this.lblPullExt = new System.Windows.Forms.Label();
            this.txtPullExt = new System.Windows.Forms.TextBox();
            this.btnOk = new System.Windows.Forms.Button();
            this.btnCancel = new System.Windows.Forms.Button();
            this.folderBrowserDialog1 = new System.Windows.Forms.FolderBrowserDialog();
            this.label1 = new System.Windows.Forms.Label();
            this.grpEndpoint.SuspendLayout();
            this.grpReceive.SuspendLayout();
            this.grpPull.SuspendLayout();
            this.SuspendLayout();
            // 
            // grpEndpoint
            // 
            this.grpEndpoint.Controls.Add(this.lblIp);
            this.grpEndpoint.Controls.Add(this.cmbIp);
            this.grpEndpoint.Controls.Add(this.lblPort);
            this.grpEndpoint.Controls.Add(this.txtPort);
            this.grpEndpoint.Location = new System.Drawing.Point(12, 8);
            this.grpEndpoint.Name = "grpEndpoint";
            this.grpEndpoint.Size = new System.Drawing.Size(436, 84);
            this.grpEndpoint.TabIndex = 0;
            this.grpEndpoint.TabStop = false;
            this.grpEndpoint.Text = "服务端点";
            // 
            // lblIp
            // 
            this.lblIp.AutoSize = true;
            this.lblIp.Location = new System.Drawing.Point(15, 22);
            this.lblIp.Name = "lblIp";
            this.lblIp.Size = new System.Drawing.Size(31, 15);
            this.lblIp.TabIndex = 0;
            this.lblIp.Text = "IP:";
            // 
            // cmbIp
            // 
            this.cmbIp.Location = new System.Drawing.Point(72, 19);
            this.cmbIp.Name = "cmbIp";
            this.cmbIp.Size = new System.Drawing.Size(290, 23);
            this.cmbIp.TabIndex = 1;
            // 
            // lblPort
            // 
            this.lblPort.AutoSize = true;
            this.lblPort.Location = new System.Drawing.Point(15, 52);
            this.lblPort.Name = "lblPort";
            this.lblPort.Size = new System.Drawing.Size(45, 15);
            this.lblPort.TabIndex = 2;
            this.lblPort.Text = "端口:";
            // 
            // txtPort
            // 
            this.txtPort.Location = new System.Drawing.Point(72, 49);
            this.txtPort.Name = "txtPort";
            this.txtPort.Size = new System.Drawing.Size(120, 25);
            this.txtPort.TabIndex = 3;
            // 
            // grpReceive
            // 
            this.grpReceive.Controls.Add(this.lblSaveDir);
            this.grpReceive.Controls.Add(this.txtSaveDir);
            this.grpReceive.Controls.Add(this.btnBrowse);
            this.grpReceive.Controls.Add(this.lblTextAction);
            this.grpReceive.Controls.Add(this.cmbTextAction);
            this.grpReceive.Controls.Add(this.lblFileAction);
            this.grpReceive.Controls.Add(this.cmbFileAction);
            this.grpReceive.Controls.Add(this.chkShowBalloon);
            this.grpReceive.Controls.Add(this.chkRunAtStartup);
            this.grpReceive.Location = new System.Drawing.Point(12, 100);
            this.grpReceive.Name = "grpReceive";
            this.grpReceive.Size = new System.Drawing.Size(436, 150);
            this.grpReceive.TabIndex = 1;
            this.grpReceive.TabStop = false;
            this.grpReceive.Text = "接收";
            // 
            // lblSaveDir
            // 
            this.lblSaveDir.AutoSize = true;
            this.lblSaveDir.Location = new System.Drawing.Point(15, 26);
            this.lblSaveDir.Name = "lblSaveDir";
            this.lblSaveDir.Size = new System.Drawing.Size(75, 15);
            this.lblSaveDir.TabIndex = 0;
            this.lblSaveDir.Text = "保存目录:";
            // 
            // txtSaveDir
            // 
            this.txtSaveDir.Location = new System.Drawing.Point(96, 22);
            this.txtSaveDir.Name = "txtSaveDir";
            this.txtSaveDir.Size = new System.Drawing.Size(285, 25);
            this.txtSaveDir.TabIndex = 1;
            // 
            // btnBrowse
            // 
            this.btnBrowse.Location = new System.Drawing.Point(387, 22);
            this.btnBrowse.Name = "btnBrowse";
            this.btnBrowse.Size = new System.Drawing.Size(32, 25);
            this.btnBrowse.TabIndex = 2;
            this.btnBrowse.Text = "...";
            // 
            // lblTextAction
            // 
            this.lblTextAction.AutoSize = true;
            this.lblTextAction.Location = new System.Drawing.Point(15, 56);
            this.lblTextAction.Name = "lblTextAction";
            this.lblTextAction.Size = new System.Drawing.Size(90, 15);
            this.lblTextAction.TabIndex = 3;
            this.lblTextAction.Text = "收到文本时:";
            // 
            // cmbTextAction
            // 
            this.cmbTextAction.Location = new System.Drawing.Point(111, 53);
            this.cmbTextAction.Name = "cmbTextAction";
            this.cmbTextAction.Size = new System.Drawing.Size(190, 23);
            this.cmbTextAction.TabIndex = 4;
            // 
            // lblFileAction
            // 
            this.lblFileAction.AutoSize = true;
            this.lblFileAction.Location = new System.Drawing.Point(15, 86);
            this.lblFileAction.Name = "lblFileAction";
            this.lblFileAction.Size = new System.Drawing.Size(90, 15);
            this.lblFileAction.TabIndex = 5;
            this.lblFileAction.Text = "收到文件时:";
            // 
            // cmbFileAction
            // 
            this.cmbFileAction.Location = new System.Drawing.Point(111, 82);
            this.cmbFileAction.Name = "cmbFileAction";
            this.cmbFileAction.Size = new System.Drawing.Size(190, 23);
            this.cmbFileAction.TabIndex = 6;
            // 
            // chkShowBalloon
            // 
            this.chkShowBalloon.AutoSize = true;
            this.chkShowBalloon.Location = new System.Drawing.Point(18, 114);
            this.chkShowBalloon.Name = "chkShowBalloon";
            this.chkShowBalloon.Size = new System.Drawing.Size(119, 19);
            this.chkShowBalloon.TabIndex = 7;
            this.chkShowBalloon.Text = "显示气泡通知";
            // 
            // chkRunAtStartup
            // 
            this.chkRunAtStartup.AutoSize = true;
            this.chkRunAtStartup.Location = new System.Drawing.Point(170, 114);
            this.chkRunAtStartup.Name = "chkRunAtStartup";
            this.chkRunAtStartup.Size = new System.Drawing.Size(119, 19);
            this.chkRunAtStartup.TabIndex = 8;
            this.chkRunAtStartup.Text = "开机自动启动";
            // 
            // grpPull
            // 
            this.grpPull.Controls.Add(this.label1);
            this.grpPull.Controls.Add(this.linkEtpStatus);
            this.grpPull.Controls.Add(this.chkDownload);
            this.grpPull.Controls.Add(this.chkEtp);
            this.grpPull.Controls.Add(this.lblPullExt);
            this.grpPull.Controls.Add(this.txtPullExt);
            this.grpPull.Location = new System.Drawing.Point(12, 258);
            this.grpPull.Name = "grpPull";
            this.grpPull.Size = new System.Drawing.Size(436, 224);
            this.grpPull.TabIndex = 2;
            this.grpPull.TabStop = false;
            this.grpPull.Text = "设置ETP";
            // 
            // linkEtpStatus
            // 
            this.linkEtpStatus.AutoSize = true;
            this.linkEtpStatus.Location = new System.Drawing.Point(97, 36);
            this.linkEtpStatus.Name = "linkEtpStatus";
            this.linkEtpStatus.Size = new System.Drawing.Size(76, 15);
            this.linkEtpStatus.TabIndex = 5;
            this.linkEtpStatus.TabStop = true;
            this.linkEtpStatus.Text = "刷新中...";
            // 
            // chkDownload
            // 
            this.chkDownload.AutoSize = true;
            this.chkDownload.Location = new System.Drawing.Point(18, 115);
            this.chkDownload.Name = "chkDownload";
            this.chkDownload.Size = new System.Drawing.Size(352, 19);
            this.chkDownload.TabIndex = 0;
            this.chkDownload.Text = "启用远程文件下载（允许手机端读取/下载文件）";
            // 
            // chkEtp
            // 
            this.chkEtp.AutoSize = true;
            this.chkEtp.Location = new System.Drawing.Point(18, 84);
            this.chkEtp.Name = "chkEtp";
            this.chkEtp.Size = new System.Drawing.Size(352, 19);
            this.chkEtp.TabIndex = 0;
            this.chkEtp.Text = "启用远程文件浏览（允许手机端浏览/搜索文件）";
            // 
            // lblPullExt
            // 
            this.lblPullExt.AutoSize = true;
            this.lblPullExt.Location = new System.Drawing.Point(15, 149);
            this.lblPullExt.Name = "lblPullExt";
            this.lblPullExt.Size = new System.Drawing.Size(157, 15);
            this.lblPullExt.TabIndex = 1;
            this.lblPullExt.Text = "仅允许下载以下类型：";
            // 
            // txtPullExt
            // 
            this.txtPullExt.Location = new System.Drawing.Point(18, 176);
            this.txtPullExt.Name = "txtPullExt";
            this.txtPullExt.Size = new System.Drawing.Size(290, 25);
            this.txtPullExt.TabIndex = 2;
            // 
            // btnOk
            // 
            this.btnOk.DialogResult = System.Windows.Forms.DialogResult.OK;
            this.btnOk.Location = new System.Drawing.Point(266, 516);
            this.btnOk.Name = "btnOk";
            this.btnOk.Size = new System.Drawing.Size(75, 23);
            this.btnOk.TabIndex = 3;
            this.btnOk.Text = "确定";
            // 
            // btnCancel
            // 
            this.btnCancel.DialogResult = System.Windows.Forms.DialogResult.Cancel;
            this.btnCancel.Location = new System.Drawing.Point(356, 516);
            this.btnCancel.Name = "btnCancel";
            this.btnCancel.Size = new System.Drawing.Size(75, 23);
            this.btnCancel.TabIndex = 4;
            this.btnCancel.Text = "取消";
            // 
            // label1
            // 
            this.label1.AutoSize = true;
            this.label1.Location = new System.Drawing.Point(15, 36);
            this.label1.Name = "label1";
            this.label1.Size = new System.Drawing.Size(76, 15);
            this.label1.TabIndex = 6;
            this.label1.Text = "ETP状态：";
            // 
            // SettingsDialog
            // 
            this.AcceptButton = this.btnOk;
            this.CancelButton = this.btnCancel;
            this.ClientSize = new System.Drawing.Size(460, 564);
            this.Controls.Add(this.grpEndpoint);
            this.Controls.Add(this.grpReceive);
            this.Controls.Add(this.grpPull);
            this.Controls.Add(this.btnOk);
            this.Controls.Add(this.btnCancel);
            this.FormBorderStyle = System.Windows.Forms.FormBorderStyle.FixedDialog;
            this.MaximizeBox = false;
            this.MinimizeBox = false;
            this.Name = "SettingsDialog";
            this.StartPosition = System.Windows.Forms.FormStartPosition.CenterParent;
            this.Text = "设置";
            this.grpEndpoint.ResumeLayout(false);
            this.grpEndpoint.PerformLayout();
            this.grpReceive.ResumeLayout(false);
            this.grpReceive.PerformLayout();
            this.grpPull.ResumeLayout(false);
            this.grpPull.PerformLayout();
            this.ResumeLayout(false);

        }

        private void BtnBrowse_Click(object sender, EventArgs e)
        {
            folderBrowserDialog1.SelectedPath = txtSaveDir.Text;
            if (folderBrowserDialog1.ShowDialog() == DialogResult.OK)
            {
                txtSaveDir.Text = folderBrowserDialog1.SelectedPath;
            }
        }

        /// <summary>弹出 ETP 配置向导以完成下载/安装/启动流程。</summary>
        private DialogResult ShowEtpWizard(Form ownerForm=null)
        {
            if (IsDisposed) return DialogResult.Cancel;
            using (var wizard = new EtpSetupWizard())
            {
                return wizard.ShowDialog(ownerForm==null?this:ownerForm);
            }
        }

        /// <summary>窗口首次显示后异步探测 ETP 是否已配置妥当，据此勾选。</summary>
        protected override void OnShown(EventArgs e)
        {
            base.OnShown(e);
        }

        private void BeginDetectEtp()
        {
            EtpBootstrap bo = new EtpBootstrap();
            var locator = _etpLocator;
            Task.Run(() =>
                {
                    while (!(this.Disposing || this.IsDisposed))
                    {
                        if (!etpRelatedChkChanged)
                        {
                            Thread.Sleep(600);
                            continue;
                        }
                        etpRelatedChkChanged = false;
                        try
                        {
                            this.Invoke(new Action(() =>
                            {
                                if (!chkEtp.Checked)
                                {
                                    bo.ExitEverything();
                                    this.linkEtpStatus.Text = "已停止";
                                    return;
                                }
                                this.linkEtpStatus.Text = "状态检查...";
                                var running = locator.IsEtpRunning(bo.GetEtpPort());
                                this.linkEtpStatus.Text = running ? "已就绪" : "未就绪";
                            }));
                        }
                        catch(Exception ex)
                        {
                            Thread.Sleep(600);
                            etpRelatedChkChanged = true;
                        }

                    }
                });
        }

        public new DialogResult ShowDialog()
        {
            var result = base.ShowDialog();
            if (result == DialogResult.OK)
            {
                Port = int.TryParse(txtPort.Text, out int p) ? p : _currentPort;
                SelectedIp = cmbIp.SelectedItem is IpOption ipOpt ? ipOpt.Ip : _currentIp;
                SaveDir = txtSaveDir.Text;
                TextAction = ((ActionOption)cmbTextAction.SelectedItem).Value;
                FileAction = ((ActionOption)cmbFileAction.SelectedItem).Value;
                ShowBalloon = chkShowBalloon.Checked;
                RunAtStartup = chkRunAtStartup.Checked;
                EtpSearchEnabled = chkEtp.Checked;
                EtpPullEnabled = chkDownload.Checked;
                PullAllowedExtensions = txtPullExt.Text;
            }
            if (EtpSearchEnabled || EtpPullEnabled) {
                if (!_etpLocator.IsEtpRunning(bo.GetEtpPort())) { 
                    bo.SetEtpPluginEnabled(chkEtp.Checked, this.chkDownload.Checked);
                    ShowEtpWizard(this.ParentForm);
                }
            }
            return result;
        }

        private GroupBox grpEndpoint;
        private GroupBox grpReceive;
        private GroupBox grpPull;
        private Label lblIp;
        private ComboBox cmbIp;
        private Label lblPort;
        private TextBox txtPort;
        private Label lblSaveDir;
        private TextBox txtSaveDir;
        private Button btnBrowse;
        private Label lblTextAction;
        private ComboBox cmbTextAction;
        private Label lblFileAction;
        private ComboBox cmbFileAction;
        private CheckBox chkShowBalloon;
        private CheckBox chkRunAtStartup;
        private CheckBox chkEtp;
        private Label lblPullExt;
        private TextBox txtPullExt;
        private Button btnOk;
        private Button btnCancel;
        private FolderBrowserDialog folderBrowserDialog1;
        private bool etpRelatedChkChanged = true;
        EtpBootstrap bo = new EtpBootstrap();
        private void chkEtp_CheckedChanged(object sender, EventArgs e)
        {
            try
            {
                
                if (!chkEtp.Checked)
                {
                    this.chkDownload.Checked = false; // chkDownload depends On checked chkEtp;
                    return;
                }
                bo.SetEtpPluginEnabled(chkEtp.Checked, this.chkDownload.Checked);
                ShowEtpWizard();
            }
            catch (Exception ex)
            {

            }
            finally
            {
                etpRelatedChkChanged = true;
            }
        }
        private void chkEtpDownload_CheckedChanged(object sender, EventArgs e)
        {
            try
            {
                chkDownload.CheckedChanged -= chkEtpDownload_CheckedChanged;
                chkEtp.CheckedChanged -= chkEtp_CheckedChanged;

                // 取消勾选无需校验可用性，直接放行。
                if (!chkDownload.Checked)
                {
                    try
                    {
                        bo.SetEtpPluginEnabled(chkEtp.Checked, false);
                        if (!chkEtp.Checked) {
                            etpRelatedChkChanged = true;
                            return;
                        }
                        bo.RestartEverything();
                    }
                    catch (Exception ex)
                    {

                    }
                    return;
                }
                chkEtp.Checked = true; // chkDownload depends On checked chkEtp;
                bo.SetEtpPluginEnabled(chkEtp.Checked, this.chkDownload.Checked);
                ShowEtpWizard();
            }
            finally
            {
                etpRelatedChkChanged = true;
                chkDownload.CheckedChanged += chkEtpDownload_CheckedChanged;
                chkEtp.CheckedChanged += chkEtp_CheckedChanged;
            }
        }
    }
}
