using System;
using System.Drawing;
using System.Threading;
using System.Windows.Forms;
using ShareToPcReciver.Config;
using ShareToPcReciver.Net;
using static ShareToPcReciver.Net.TransferServer;

namespace ShareToPcReciver
{
    internal class AuthDialog : Form
    {
        private Label labelSummary;
        private Label lblExpire;
        private ComboBox cmbExpire;
        private Label lblTextAction;
        private ComboBox cmbTextAction;
        private LinkLabel lnkBlock;
        private Button btnReject;
        private Button btnOnce;
        private CheckBox chkAlways;
        private System.Windows.Forms.Timer countdownTimer;
        private int countdownValue;
        private CancellationToken _cancellation;
        private Panel panel1;
        private AuthResult _result;

        public AuthDialog()
        {
            InitializeComponent();
        }

        private void InitializeComponent()
        {
            this.labelSummary = new System.Windows.Forms.Label();
            this.lblExpire = new System.Windows.Forms.Label();
            this.cmbExpire = new System.Windows.Forms.ComboBox();
            this.lblTextAction = new System.Windows.Forms.Label();
            this.cmbTextAction = new System.Windows.Forms.ComboBox();
            this.lnkBlock = new System.Windows.Forms.LinkLabel();
            this.btnReject = new System.Windows.Forms.Button();
            this.btnOnce = new System.Windows.Forms.Button();
            this.chkAlways = new System.Windows.Forms.CheckBox();
            this.panel1 = new System.Windows.Forms.Panel();
            this.panel1.SuspendLayout();
            this.SuspendLayout();
            // 
            // labelSummary
            // 
            this.labelSummary.Location = new System.Drawing.Point(20, 20);
            this.labelSummary.Name = "labelSummary";
            this.labelSummary.Size = new System.Drawing.Size(380, 70);
            this.labelSummary.TabIndex = 0;
            // 
            // lblExpire
            // 
            this.lblExpire.Location = new System.Drawing.Point(34, 105);
            this.lblExpire.Name = "lblExpire";
            this.lblExpire.Size = new System.Drawing.Size(85, 25);
            this.lblExpire.TabIndex = 1;
            this.lblExpire.Text = "授权过期:";
            this.lblExpire.TextAlign = System.Drawing.ContentAlignment.MiddleLeft;
            // 
            // cmbExpire
            // 
            this.cmbExpire.DropDownStyle = System.Windows.Forms.ComboBoxStyle.DropDownList;
            this.cmbExpire.Location = new System.Drawing.Point(125, 107);
            this.cmbExpire.Name = "cmbExpire";
            this.cmbExpire.Size = new System.Drawing.Size(140, 23);
            this.cmbExpire.TabIndex = 2;
            // 
            // lblTextAction
            // 
            this.lblTextAction.Location = new System.Drawing.Point(20, 140);
            this.lblTextAction.Name = "lblTextAction";
            this.lblTextAction.Size = new System.Drawing.Size(96, 25);
            this.lblTextAction.TabIndex = 3;
            this.lblTextAction.Text = "收到文本时:";
            this.lblTextAction.TextAlign = System.Drawing.ContentAlignment.MiddleLeft;
            // 
            // cmbTextAction
            // 
            this.cmbTextAction.DropDownStyle = System.Windows.Forms.ComboBoxStyle.DropDownList;
            this.cmbTextAction.Location = new System.Drawing.Point(125, 140);
            this.cmbTextAction.Name = "cmbTextAction";
            this.cmbTextAction.Size = new System.Drawing.Size(140, 23);
            this.cmbTextAction.TabIndex = 4;
            // 
            // lnkBlock
            // 
            this.lnkBlock.ActiveLinkColor = System.Drawing.Color.Red;
            this.lnkBlock.AutoSize = true;
            this.lnkBlock.LinkColor = System.Drawing.Color.Blue;
            this.lnkBlock.Location = new System.Drawing.Point(20, 25);
            this.lnkBlock.Name = "lnkBlock";
            this.lnkBlock.Size = new System.Drawing.Size(82, 15);
            this.lnkBlock.TabIndex = 5;
            this.lnkBlock.TabStop = true;
            this.lnkBlock.Text = "拒绝并拉黑";
            this.lnkBlock.VisitedLinkColor = System.Drawing.Color.Red;
            this.lnkBlock.Click += new System.EventHandler(this.LnkBlock_Click);
            // 
            // btnReject
            // 
            this.btnReject.DialogResult = System.Windows.Forms.DialogResult.No;
            this.btnReject.Location = new System.Drawing.Point(125, 17);
            this.btnReject.Name = "btnReject";
            this.btnReject.Size = new System.Drawing.Size(112, 30);
            this.btnReject.TabIndex = 6;
            this.btnReject.Text = "拒绝 (30s)";
            // 
            // btnOnce
            // 
            this.btnOnce.DialogResult = System.Windows.Forms.DialogResult.Yes;
            this.btnOnce.Location = new System.Drawing.Point(260, 17);
            this.btnOnce.Name = "btnOnce";
            this.btnOnce.Size = new System.Drawing.Size(111, 30);
            this.btnOnce.TabIndex = 7;
            this.btnOnce.Text = "接收(&A)";
            // 
            // chkAlways
            // 
            this.chkAlways.AutoSize = true;
            this.chkAlways.Location = new System.Drawing.Point(125, 182);
            this.chkAlways.Name = "chkAlways";
            this.chkAlways.Size = new System.Drawing.Size(134, 19);
            this.chkAlways.TabIndex = 8;
            this.chkAlways.Text = "接收本次及后续";
            // 
            // panel1
            // 
            this.panel1.BackColor = System.Drawing.SystemColors.ControlLightLight;
            this.panel1.Controls.Add(this.btnReject);
            this.panel1.Controls.Add(this.btnOnce);
            this.panel1.Controls.Add(this.lnkBlock);
            this.panel1.Dock = System.Windows.Forms.DockStyle.Bottom;
            this.panel1.Location = new System.Drawing.Point(0, 303);
            this.panel1.Name = "panel1";
            this.panel1.Size = new System.Drawing.Size(420, 62);
            this.panel1.TabIndex = 9;
            // 
            // AuthDialog
            // 
            this.AcceptButton = this.btnReject;
            this.CancelButton = this.btnReject;
            this.ClientSize = new System.Drawing.Size(420, 365);
            this.Controls.Add(this.panel1);
            this.Controls.Add(this.labelSummary);
            this.Controls.Add(this.lblExpire);
            this.Controls.Add(this.cmbExpire);
            this.Controls.Add(this.lblTextAction);
            this.Controls.Add(this.cmbTextAction);
            this.Controls.Add(this.chkAlways);
            this.FormBorderStyle = System.Windows.Forms.FormBorderStyle.FixedDialog;
            this.MaximizeBox = false;
            this.MinimizeBox = false;
            this.Name = "AuthDialog";
            this.ShowInTaskbar = false;
            this.StartPosition = System.Windows.Forms.FormStartPosition.CenterParent;
            this.Text = "文件传输请求";
            this.TopMost = true;
            this.panel1.ResumeLayout(false);
            this.panel1.PerformLayout();
            this.ResumeLayout(false);
            this.PerformLayout();

        }

        public static AuthResult ShowDialog(AuthInfo req, CancellationToken cancellation)
        {
            using var dlg = new AuthDialog();
            dlg._cancellation = cancellation;
            dlg._result = new AuthResult { authJobInfo = req };

            dlg.labelSummary.Text = req.Summary;

            dlg.cmbExpire.BindingContext = new BindingContext();
            dlg.cmbExpire.Items.AddRange(new object[]
            {
                new { Text = "永不过期", Days = 0 },
                new { Text = "1 天", Days = 1 },
                new { Text = "3 天", Days = 3 },
                new { Text = "7 天", Days = 7 },
                new { Text = "30 天", Days = 30 },
                new { Text = "90 天", Days = 90 },
            });
            dlg.cmbExpire.DisplayMember = "Text";
            dlg.cmbExpire.SelectedIndex = 3;

            dlg.cmbTextAction.Items.AddRange(new object[] { "跟随全局设定", "无操作", "复制文本到剪贴板" });
            dlg.cmbTextAction.SelectedIndex = 0;

            dlg.countdownValue = 30;
            dlg.countdownTimer = new System.Windows.Forms.Timer { Interval = 1000 };
            dlg.countdownTimer.Tick += (s, ev) =>
            {
                dlg.countdownValue--;
                if (dlg.countdownValue <= 0 || cancellation.IsCancellationRequested)
                {
                    dlg.countdownTimer.Stop();
                    dlg.btnReject.PerformClick();
                }
                else
                {
                    dlg.btnReject.Text = $"拒绝 ({dlg.countdownValue}s)";
                }
            };
            dlg.countdownTimer.Start();
            dlg.Disposed += (s, ev) => { dlg.countdownTimer.Stop(); dlg.countdownTimer.Dispose(); };

            var dr = dlg.ShowDialog();
            dlg.countdownTimer.Stop();

            switch (dr)
            {
                case DialogResult.Abort:
                    dlg._result.IsBlocked = true;
                    dlg._result.Status = "REJECTED";
                    break;
                case DialogResult.Yes:
                    dlg._result.Status = "ACCEPTED";
                    dlg._result.TextAction = GetTextAction(dlg.cmbTextAction.SelectedIndex);
                    if (dlg.chkAlways.Checked)
                    {
                        dynamic selected = dlg.cmbExpire.SelectedItem;
                        int days = selected.Days;
                        if (days > 0)
                            dlg._result.ExpiresAt = DateTimeOffset.UtcNow.AddDays(days).ToUnixTimeMilliseconds();
                        dlg._result.Credits = -1;
                        dlg._result.MaxItemsPerJob = int.MaxValue;
                        dlg._result.MaxBytesPerJob = long.MaxValue;
                    }
                    else
                    {
                        dlg._result.Credits = 1;
                    }
                    break;
                default:
                    dlg._result.Status = "REJECTED";
                    break;
            }

            return dlg._result;
        }

        private static ArrivalAction? GetTextAction(int index)
        {
            return index switch
            {
                1 => ArrivalAction.None,
                2 => ArrivalAction.CopyText,
                _ => null
            };
        }

        private void LnkBlock_Click(object sender, EventArgs e)
        {
            this.DialogResult = DialogResult.Abort;
            this.Close();
        }
    }
}
