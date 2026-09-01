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
        private Button btnReject;
        private Button btnAccept;
        private System.Windows.Forms.Timer countdownTimer;
        private int countdownValue;
        private CancellationToken _cancellation;
        private AuthResult _result;

        public AuthDialog()
        {
            InitializeComponent();
        }

        private void InitializeComponent()
        {
            this.labelSummary = new System.Windows.Forms.Label();
            this.btnReject = new System.Windows.Forms.Button();
            this.btnAccept = new System.Windows.Forms.Button();
            this.SuspendLayout();
            // 
            // labelSummary
            // 
            this.labelSummary.Location = new System.Drawing.Point(20, 20);
            this.labelSummary.Name = "labelSummary";
            this.labelSummary.Size = new System.Drawing.Size(380, 120);
            this.labelSummary.TabIndex = 0;
            // 
            // btnReject
            // 
            this.btnReject.DialogResult = System.Windows.Forms.DialogResult.No;
            this.btnReject.Location = new System.Drawing.Point(180, 155);
            this.btnReject.Name = "btnReject";
            this.btnReject.Size = new System.Drawing.Size(100, 35);
            this.btnReject.TabIndex = 1;
            this.btnReject.Text = "拒绝 (30s)";
            // 
            // btnAccept
            // 
            this.btnAccept.DialogResult = System.Windows.Forms.DialogResult.Yes;
            this.btnAccept.Location = new System.Drawing.Point(290, 155);
            this.btnAccept.Name = "btnAccept";
            this.btnAccept.Size = new System.Drawing.Size(100, 35);
            this.btnAccept.TabIndex = 2;
            this.btnAccept.Text = "接收";
            // 
            // AuthDialog
            // 
            this.AcceptButton = this.btnAccept;
            this.CancelButton = this.btnReject;
            this.ClientSize = new System.Drawing.Size(410, 210);
            this.Controls.Add(this.labelSummary);
            this.Controls.Add(this.btnReject);
            this.Controls.Add(this.btnAccept);
            this.FormBorderStyle = System.Windows.Forms.FormBorderStyle.FixedDialog;
            this.MaximizeBox = false;
            this.MinimizeBox = false;
            this.Name = "AuthDialog";
            this.ShowInTaskbar = false;
            this.StartPosition = System.Windows.Forms.FormStartPosition.CenterParent;
            this.Text = "有人发文件来了";
            this.TopMost = true;
            this.ResumeLayout(false);
        }

        public static AuthResult ShowDialog(IWin32Window owner, AuthInfo req, CancellationToken cancellation)
        {
            using var dlg = new AuthDialog();
            dlg._cancellation = cancellation;
            dlg._result = new AuthResult { authJobInfo = req };

            dlg.labelSummary.Text = req.Summary;

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

            var dr = dlg.ShowDialog(owner);
            dlg.countdownTimer.Stop();

            switch (dr)
            {
                case DialogResult.Yes:
                    dlg._result.Status = "ACCEPTED";
                    dlg._result.Credits = 1;
                    break;
                default:
                    dlg._result.Status = "REJECTED";
                    break;
            }

            return dlg._result;
        }
    }
}
