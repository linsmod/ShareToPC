using System;
using System.Collections.Generic;
using System.Drawing;
using System.Windows.Forms;
using ShareToPcReciver.Config;

namespace ShareToPcReciver
{
    public class SettingsDialog : Form
    {
        public int Port { get; private set; }
        public string SaveDir { get; private set; }
        public ArrivalAction TextAction { get; private set; }
        public ArrivalAction FileAction { get; private set; }
        public bool ShowBalloon { get; private set; }
        public bool RunAtStartup { get; private set; }

        private readonly int _currentPort;

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

        public SettingsDialog(AppConfig config, int currentPort)
        {
            _currentPort = currentPort;
            Port = currentPort;
            SaveDir = config.SaveDir;
            TextAction = config.TextAction;
            FileAction = config.FileAction;
            ShowBalloon = config.ShowBalloon;
            RunAtStartup = config.RunAtStartup;
            InitializeComponent();

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

        private void InitializeComponent()
        {
            this.lblPort = new Label();
            this.txtPort = new TextBox();
            this.lblSaveDir = new Label();
            this.txtSaveDir = new TextBox();
            this.btnBrowse = new Button();
            this.chkShowBalloon = new CheckBox();
            this.chkRunAtStartup = new CheckBox();
            this.lblTextAction = new Label();
            this.cmbTextAction = new ComboBox();
            this.lblFileAction = new Label();
            this.cmbFileAction = new ComboBox();
            this.btnOk = new Button();
            this.btnCancel = new Button();
            this.folderBrowserDialog1 = new FolderBrowserDialog();
            this.SuspendLayout();

            this.lblPort.Text = "端口:";
            this.lblPort.Location = new Point(12, 15);
            this.lblPort.AutoSize = true;

            this.txtPort.Text = Port.ToString();
            this.txtPort.Location = new Point(80, 12);
            this.txtPort.Size = new Size(80, 23);

            this.lblSaveDir.Text = "保存目录:";
            this.lblSaveDir.Location = new Point(12, 45);
            this.lblSaveDir.AutoSize = true;

            this.txtSaveDir.Text = SaveDir;
            this.txtSaveDir.Location = new Point(80, 42);
            this.txtSaveDir.Size = new Size(280, 23);

            this.btnBrowse.Text = "...";
            this.btnBrowse.Location = new Point(368, 41);
            this.btnBrowse.Size = new Size(32, 23);
            this.btnBrowse.Click += BtnBrowse_Click;

            this.chkShowBalloon.Text = "显示气泡通知";
            this.chkShowBalloon.Location = new Point(12, 75);
            this.chkShowBalloon.Checked = ShowBalloon;

            this.chkRunAtStartup.Text = "开机自动启动";
            this.chkRunAtStartup.Location = new Point(12, 99);
            this.chkRunAtStartup.Checked = RunAtStartup;

            this.lblTextAction.Text = "收到文本时:";
            this.lblTextAction.Location = new Point(12, 133);
            this.lblTextAction.AutoSize = true;

            this.cmbTextAction.Location = new Point(100, 130);
            this.cmbTextAction.Size = new Size(190, 23);

            this.lblFileAction.Text = "收到文件时:";
            this.lblFileAction.Location = new Point(12, 163);
            this.lblFileAction.AutoSize = true;

            this.cmbFileAction.Location = new Point(100, 160);
            this.cmbFileAction.Size = new Size(190, 23);

            this.btnOk.Text = "确定";
            this.btnOk.Location = new Point(220, 200);
            this.btnOk.DialogResult = DialogResult.OK;

            this.btnCancel.Text = "取消";
            this.btnCancel.Location = new Point(310, 200);
            this.btnCancel.DialogResult = DialogResult.Cancel;

            this.Controls.Add(this.lblPort);
            this.Controls.Add(this.txtPort);
            this.Controls.Add(this.lblSaveDir);
            this.Controls.Add(this.txtSaveDir);
            this.Controls.Add(this.btnBrowse);
            this.Controls.Add(this.chkShowBalloon);
            this.Controls.Add(this.chkRunAtStartup);
            this.Controls.Add(this.lblTextAction);
            this.Controls.Add(this.cmbTextAction);
            this.Controls.Add(this.lblFileAction);
            this.Controls.Add(this.cmbFileAction);
            this.Controls.Add(this.btnOk);
            this.Controls.Add(this.btnCancel);

            this.AcceptButton = this.btnOk;
            this.CancelButton = this.btnCancel;
            this.ClientSize = new Size(420, 240);
            this.FormBorderStyle = FormBorderStyle.FixedDialog;
            this.MaximizeBox = false;
            this.MinimizeBox = false;
            this.StartPosition = FormStartPosition.CenterParent;
            this.Text = "设置";
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
                SaveDir = txtSaveDir.Text;
                TextAction = ((ActionOption)cmbTextAction.SelectedItem).Value;
                FileAction = ((ActionOption)cmbFileAction.SelectedItem).Value;
                ShowBalloon = chkShowBalloon.Checked;
                RunAtStartup = chkRunAtStartup.Checked;
            }
            return result;
        }

        private Label lblPort;
        private TextBox txtPort;
        private Label lblSaveDir;
        private TextBox txtSaveDir;
        private Button btnBrowse;
        private CheckBox chkShowBalloon;
        private CheckBox chkRunAtStartup;
        private Label lblTextAction;
        private ComboBox cmbTextAction;
        private Label lblFileAction;
        private ComboBox cmbFileAction;
        private Button btnOk;
        private Button btnCancel;
        private FolderBrowserDialog folderBrowserDialog1;
    }
}
