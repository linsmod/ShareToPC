using System;
using System.Collections.Generic;
using System.Drawing;
using System.Windows.Forms;
using ShareToPcReciver.Config;
using ShareToPcReciver.Net;

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
        public bool PullEnabled { get; private set; }
        public string PullAllowedExtensions { get; private set; }

        private readonly int _currentPort;
        private readonly string _currentIp;

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
            PullEnabled = config.PullEnabled;
            PullAllowedExtensions = config.PullAllowedExtensions;
            InitializeComponent();

            // 将依赖外部数据的赋值放在 InitializeComponent 之后，避免设计器序列化错误
            txtPort.Text = Port.ToString();
            PopulateIpCombo();
            txtSaveDir.Text = SaveDir;
            chkShowBalloon.Checked = ShowBalloon;
            chkRunAtStartup.Checked = RunAtStartup;
            chkPullEnabled.Checked = PullEnabled;
            txtPullExt.Text = PullAllowedExtensions;

            BindActionCombo(cmbTextAction, TextActionOptions, TextAction);
            BindActionCombo(cmbFileAction, FileActionOptions, FileAction);
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
            this.grpEndpoint = new GroupBox();
            this.grpReceive = new GroupBox();
            this.grpPull = new GroupBox();
            this.lblIp = new Label();
            this.cmbIp = new ComboBox();
            this.lblPort = new Label();
            this.txtPort = new TextBox();
            this.lblSaveDir = new Label();
            this.txtSaveDir = new TextBox();
            this.btnBrowse = new Button();
            this.lblTextAction = new Label();
            this.cmbTextAction = new ComboBox();
            this.lblFileAction = new Label();
            this.cmbFileAction = new ComboBox();
            this.chkShowBalloon = new CheckBox();
            this.chkRunAtStartup = new CheckBox();
            this.chkPullEnabled = new CheckBox();
            this.lblPullExt = new Label();
            this.txtPullExt = new TextBox();
            this.btnOk = new Button();
            this.btnCancel = new Button();
            this.folderBrowserDialog1 = new FolderBrowserDialog();
            this.grpEndpoint.SuspendLayout();
            this.grpReceive.SuspendLayout();
            this.grpPull.SuspendLayout();
            this.SuspendLayout();

            // ---- 服务端点：IP 与端口 ----
            this.grpEndpoint.Text = "服务端点";
            this.grpEndpoint.Location = new Point(12, 8);
            this.grpEndpoint.Size = new Size(436, 84);

            this.lblIp.Text = "IP:";
            this.lblIp.Location = new Point(15, 22);
            this.lblIp.AutoSize = true;

            this.cmbIp.Location = new Point(48, 19);
            this.cmbIp.Size = new Size(290, 23);

            this.lblPort.Text = "端口:";
            this.lblPort.Location = new Point(15, 52);
            this.lblPort.AutoSize = true;

            this.txtPort.Location = new Point(48, 49);
            this.txtPort.Size = new Size(120, 23);

            this.grpEndpoint.Controls.Add(this.lblIp);
            this.grpEndpoint.Controls.Add(this.cmbIp);
            this.grpEndpoint.Controls.Add(this.lblPort);
            this.grpEndpoint.Controls.Add(this.txtPort);

            // ---- 接收：保存目录与到达动作 ----
            this.grpReceive.Text = "接收";
            this.grpReceive.Location = new Point(12, 100);
            this.grpReceive.Size = new Size(436, 150);

            this.lblSaveDir.Text = "保存目录:";
            this.lblSaveDir.Location = new Point(15, 26);
            this.lblSaveDir.AutoSize = true;

            this.txtSaveDir.Location = new Point(75, 23);
            this.txtSaveDir.Size = new Size(285, 23);

            this.btnBrowse.Text = "...";
            this.btnBrowse.Location = new Point(368, 22);
            this.btnBrowse.Size = new Size(32, 23);
            this.btnBrowse.Click += BtnBrowse_Click;

            this.lblTextAction.Text = "收到文本时:";
            this.lblTextAction.Location = new Point(15, 56);
            this.lblTextAction.AutoSize = true;

            this.cmbTextAction.Location = new Point(100, 53);
            this.cmbTextAction.Size = new Size(190, 23);

            this.lblFileAction.Text = "收到文件时:";
            this.lblFileAction.Location = new Point(15, 86);
            this.lblFileAction.AutoSize = true;

            this.cmbFileAction.Location = new Point(100, 83);
            this.cmbFileAction.Size = new Size(190, 23);

            this.chkShowBalloon.Text = "显示气泡通知";
            this.chkShowBalloon.Location = new Point(15, 114);
            this.chkShowBalloon.AutoSize = true;

            this.chkRunAtStartup.Text = "开机自动启动";
            this.chkRunAtStartup.Location = new Point(170, 114);
            this.chkRunAtStartup.AutoSize = true;

            this.grpReceive.Controls.Add(this.lblSaveDir);
            this.grpReceive.Controls.Add(this.txtSaveDir);
            this.grpReceive.Controls.Add(this.btnBrowse);
            this.grpReceive.Controls.Add(this.lblTextAction);
            this.grpReceive.Controls.Add(this.cmbTextAction);
            this.grpReceive.Controls.Add(this.lblFileAction);
            this.grpReceive.Controls.Add(this.cmbFileAction);
            this.grpReceive.Controls.Add(this.chkShowBalloon);
            this.grpReceive.Controls.Add(this.chkRunAtStartup);

            // ---- 手机端下载：允许开关与扩展名 ----
            this.grpPull.Text = "手机端下载";
            this.grpPull.Location = new Point(12, 258);
            this.grpPull.Size = new Size(436, 88);

            this.chkPullEnabled.Text = "允许手机端下载文件";
            this.chkPullEnabled.Location = new Point(15, 26);
            this.chkPullEnabled.AutoSize = true;

            this.lblPullExt.Text = "允许的扩展名:";
            this.lblPullExt.Location = new Point(15, 56);
            this.lblPullExt.AutoSize = true;

            this.txtPullExt.Location = new Point(100, 53);
            this.txtPullExt.Size = new Size(290, 23);

            this.grpPull.Controls.Add(this.chkPullEnabled);
            this.grpPull.Controls.Add(this.lblPullExt);
            this.grpPull.Controls.Add(this.txtPullExt);

            this.btnOk.Text = "确定";
            this.btnOk.Location = new Point(220, 356);
            this.btnOk.DialogResult = DialogResult.OK;

            this.btnCancel.Text = "取消";
            this.btnCancel.Location = new Point(310, 356);
            this.btnCancel.DialogResult = DialogResult.Cancel;

            this.Controls.Add(this.grpEndpoint);
            this.Controls.Add(this.grpReceive);
            this.Controls.Add(this.grpPull);
            this.Controls.Add(this.btnOk);
            this.Controls.Add(this.btnCancel);

            this.AcceptButton = this.btnOk;
            this.CancelButton = this.btnCancel;
            this.ClientSize = new Size(460, 390);
            this.FormBorderStyle = FormBorderStyle.FixedDialog;
            this.MaximizeBox = false;
            this.MinimizeBox = false;
            this.StartPosition = FormStartPosition.CenterParent;
            this.Text = "设置";
            this.grpPull.ResumeLayout(false);
            this.grpReceive.ResumeLayout(false);
            this.grpEndpoint.ResumeLayout(false);
            this.ResumeLayout(false);
            this.PerformLayout();
        }

        private void BtnBrowse_Click(object sender, EventArgs e)
        {
            folderBrowserDialog1.SelectedPath = txtSaveDir.Text;
            if (folderBrowserDialog1.ShowDialog() == DialogResult.OK)
            {
                txtSaveDir.Text = folderBrowserDialog1.SelectedPath;
            }
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
                PullEnabled = chkPullEnabled.Checked;
                PullAllowedExtensions = txtPullExt.Text;
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
        private CheckBox chkPullEnabled;
        private Label lblPullExt;
        private TextBox txtPullExt;
        private Button btnOk;
        private Button btnCancel;
        private FolderBrowserDialog folderBrowserDialog1;
    }
}
