namespace ShareToPcReciver
{
    partial class MainForm
    {
        private System.ComponentModel.IContainer components = null;

        protected override void Dispose(bool disposing)
        {
            if (disposing && (components != null))
            {
                components.Dispose();
            }
            base.Dispose(disposing);
        }

        #region Windows Form Designer generated code

        private void InitializeComponent()
        {
            this.components = new System.ComponentModel.Container();
            this.statusStrip = new System.Windows.Forms.StatusStrip();
            this.toolStripStatusLabel1 = new System.Windows.Forms.ToolStripStatusLabel();
            this.lblStatus = new System.Windows.Forms.ToolStripStatusLabel();
            this.lblPort = new System.Windows.Forms.ToolStripStatusLabel();
            this.lblIp = new System.Windows.Forms.ToolStripStatusLabel();
            this.btnCopyIp = new System.Windows.Forms.ToolStripButton();
            this.columnHeader1 = new System.Windows.Forms.ColumnHeader();
            this.columnHeader2 = new System.Windows.Forms.ColumnHeader();
            this.columnHeader3 = new System.Windows.Forms.ColumnHeader();
            this.columnHeader6 = new System.Windows.Forms.ColumnHeader();
            this.columnHeader7 = new System.Windows.Forms.ColumnHeader();
            this.columnHeader4 = new System.Windows.Forms.ColumnHeader();
            this.columnHeader5 = new System.Windows.Forms.ColumnHeader();
            this.btnOpenDir = new System.Windows.Forms.Button();
            this.btnDevices = new System.Windows.Forms.Button();
            this.btnSettings = new System.Windows.Forms.Button();
            this.btnMinimize = new System.Windows.Forms.Button();
            this.notifyIcon = new System.Windows.Forms.NotifyIcon(this.components);
            this.contextMenuStrip = new System.Windows.Forms.ContextMenuStrip(this.components);
            this.toolStripMenuItemOpen = new System.Windows.Forms.ToolStripMenuItem();
            this.toolStripMenuItemExit = new System.Windows.Forms.ToolStripMenuItem();
            this.saveFileDialog1 = new System.Windows.Forms.SaveFileDialog();
            this.folderBrowserDialog1 = new System.Windows.Forms.FolderBrowserDialog();
            this.lvHistory = new System.Windows.Forms.ListView();
            this.lvHistoryMenu = new System.Windows.Forms.ContextMenuStrip(this.components);
            this.menuOpenFile = new System.Windows.Forms.ToolStripMenuItem();
            this.menuOpenFolder = new System.Windows.Forms.ToolStripMenuItem();
            this.menuCopyContent = new System.Windows.Forms.ToolStripMenuItem();
            this.menuViewDetail = new System.Windows.Forms.ToolStripMenuItem();
            this.menuSeparator1 = new System.Windows.Forms.ToolStripSeparator();
            this.menuCancelTransfer = new System.Windows.Forms.ToolStripMenuItem();
            this.menuSeparator2 = new System.Windows.Forms.ToolStripSeparator();
            this.menuDeleteRecord = new System.Windows.Forms.ToolStripMenuItem();
            this.menuDeleteWithFile = new System.Windows.Forms.ToolStripMenuItem();
            this.SuspendLayout();
            // 
            // statusStrip
            // 
            this.statusStrip.Items.AddRange(new System.Windows.Forms.ToolStripItem[] {
            this.toolStripStatusLabel1,
            this.lblStatus,
            this.lblPort,
            this.lblIp,
            this.btnCopyIp});
            this.statusStrip.Location = new System.Drawing.Point(0, 374);
            this.statusStrip.Name = "statusStrip";
            this.statusStrip.Size = new System.Drawing.Size(784, 22);
            this.statusStrip.TabIndex = 0;
            this.statusStrip.Text = "statusStrip1";
            // 
            // toolStripStatusLabel1
            // 
            this.toolStripStatusLabel1.Name = "toolStripStatusLabel1";
            this.toolStripStatusLabel1.Size = new System.Drawing.Size(114, 17);
            this.toolStripStatusLabel1.Text = "状态：服务未运行";
            // 
            // lblStatus
            // 
            this.lblStatus.Name = "lblStatus";
            this.lblStatus.Size = new System.Drawing.Size(0, 17);
            // 
            // lblPort
            // 
            this.lblPort.Name = "lblPort";
            this.lblPort.Size = new System.Drawing.Size(0, 17);
            // 
            // lblIp
            // 
            this.lblIp.Name = "lblIp";
            this.lblIp.Size = new System.Drawing.Size(0, 17);
            // 
            // btnCopyIp
            // 
            this.btnCopyIp.DisplayStyle = System.Windows.Forms.ToolStripItemDisplayStyle.Text;
            this.btnCopyIp.Name = "btnCopyIp";
            this.btnCopyIp.Size = new System.Drawing.Size(44, 17);
            this.btnCopyIp.Text = "复制IP";
            this.btnCopyIp.Click += new System.EventHandler(this.BtnCopyIp_Click);
            // 
            // columnHeader1
            // 
            this.columnHeader1.Text = "时间";
            this.columnHeader1.Width = 110;
            // 
            // columnHeader2
            // 
            this.columnHeader2.Text = "类型";
            this.columnHeader2.Width = 50;
            // 
            // columnHeader3
            // 
            this.columnHeader3.Text = "名称";
            this.columnHeader3.Width = 200;
            // 
            // columnHeader6
            // 
            this.columnHeader6.Text = "扩展名";
            this.columnHeader6.Width = 60;
            // 
            // columnHeader7
            // 
            this.columnHeader7.Text = "大小";
            this.columnHeader7.Width = 75;
            // 
            // columnHeader4
            // 
            this.columnHeader4.Text = "来源";
            this.columnHeader4.Width = 110;
            // 
            // columnHeader5
            // 
            this.columnHeader5.Text = "进度";
            this.columnHeader5.Width = 100;
            // 
            // btnOpenDir
            // 
            this.btnOpenDir.Location = new System.Drawing.Point(12, 10);
            this.btnOpenDir.Name = "btnOpenDir";
            this.btnOpenDir.Size = new System.Drawing.Size(120, 25);
            this.btnOpenDir.TabIndex = 1;
            this.btnOpenDir.Text = "打开接收目录";
            this.btnOpenDir.UseVisualStyleBackColor = true;
            this.btnOpenDir.Click += new System.EventHandler(this.BtnOpenDir_Click);
            // 
            // btnDevices
            // 
            this.btnDevices.Location = new System.Drawing.Point(215, 10);
            this.btnDevices.Name = "btnDevices";
            this.btnDevices.Size = new System.Drawing.Size(75, 25);
            this.btnDevices.TabIndex = 3;
            this.btnDevices.Text = "设备管理";
            this.btnDevices.UseVisualStyleBackColor = true;
            this.btnDevices.Click += new System.EventHandler(this.BtnDevices_Click);
            // 
            // btnSettings
            // 
            this.btnSettings.Location = new System.Drawing.Point(140, 10);
            this.btnSettings.Name = "btnSettings";
            this.btnSettings.Size = new System.Drawing.Size(75, 25);
            this.btnSettings.TabIndex = 2;
            this.btnSettings.Text = "设置";
            this.btnSettings.UseVisualStyleBackColor = true;
            this.btnSettings.Click += new System.EventHandler(this.BtnSettings_Click);
            // 
            // btnMinimize
            // 
            this.btnMinimize.Anchor = ((System.Windows.Forms.AnchorStyles)((System.Windows.Forms.AnchorStyles.Top | System.Windows.Forms.AnchorStyles.Right)));
            this.btnMinimize.Location = new System.Drawing.Point(697, 10);
            this.btnMinimize.Name = "btnMinimize";
            this.btnMinimize.Size = new System.Drawing.Size(75, 25);
            this.btnMinimize.TabIndex = 4;
            this.btnMinimize.Text = "最小化";
            this.btnMinimize.UseVisualStyleBackColor = true;
            this.btnMinimize.Click += new System.EventHandler(this.BtnMinimize_Click);
            // 
            // notifyIcon
            // 
            this.notifyIcon.ContextMenuStrip = this.contextMenuStrip;
            this.notifyIcon.Text = "ShareToPcReciver";
            this.notifyIcon.Visible = false;
            this.notifyIcon.DoubleClick += new System.EventHandler(this.NotifyIcon_DoubleClick);
            // 
            // contextMenuStrip
            // 
            this.contextMenuStrip.Items.AddRange(new System.Windows.Forms.ToolStripItem[] {
            this.toolStripMenuItemOpen,
            this.toolStripMenuItemExit});
            this.contextMenuStrip.Name = "contextMenuStrip";
            this.contextMenuStrip.Size = new System.Drawing.Size(115, 70);
            // 
            // toolStripMenuItemOpen
            // 
            this.toolStripMenuItemOpen.Name = "toolStripMenuItemOpen";
            this.toolStripMenuItemOpen.Size = new System.Drawing.Size(114, 22);
            this.toolStripMenuItemOpen.Text = "打开窗口";
            this.toolStripMenuItemOpen.Click += new System.EventHandler(this.ToolStripMenuItemOpen_Click);
            // 
            // toolStripMenuItemExit
            // 
            this.toolStripMenuItemExit.Name = "toolStripMenuItemExit";
            this.toolStripMenuItemExit.Size = new System.Drawing.Size(114, 22);
            this.toolStripMenuItemExit.Text = "退出";
            this.toolStripMenuItemExit.Click += new System.EventHandler(this.ToolStripMenuItemExit_Click);
            // 
            // folderBrowserDialog1
            // 
            this.folderBrowserDialog1.Description = "选择接收目录";
            // 
            // lvHistory
            // 
            this.lvHistory.Anchor = ((System.Windows.Forms.AnchorStyles)((((System.Windows.Forms.AnchorStyles.Top | System.Windows.Forms.AnchorStyles.Bottom) 
            | System.Windows.Forms.AnchorStyles.Left) 
            | System.Windows.Forms.AnchorStyles.Right)));
            this.lvHistory.Columns.AddRange(new System.Windows.Forms.ColumnHeader[] {
            this.columnHeader1,
            this.columnHeader2,
            this.columnHeader3,
            this.columnHeader6,
            this.columnHeader7,
            this.columnHeader4,
            this.columnHeader5});
            this.lvHistory.ContextMenuStrip = this.lvHistoryMenu;
            this.lvHistory.FullRowSelect = true;
            this.lvHistory.GridLines = true;
            this.lvHistory.HideSelection = false;
            this.lvHistory.Location = new System.Drawing.Point(12, 44);
            this.lvHistory.MultiSelect = true;
            this.lvHistory.Name = "lvHistory";
            this.lvHistory.OwnerDraw = true;
            this.lvHistory.Size = new System.Drawing.Size(760, 345);
            this.lvHistory.TabIndex = 0;
            this.lvHistory.UseCompatibleStateImageBehavior = false;
            this.lvHistory.View = System.Windows.Forms.View.Details;
            this.lvHistory.DoubleClick += new System.EventHandler(this.LvHistory_DoubleClick);
            // 
            // lvHistoryMenu
            // 
            this.lvHistoryMenu.Items.AddRange(new System.Windows.Forms.ToolStripItem[] {
            this.menuOpenFile,
            this.menuOpenFolder,
            this.menuCopyContent,
            this.menuViewDetail,
            this.menuSeparator1,
            this.menuCancelTransfer,
            this.menuSeparator2,
            this.menuDeleteRecord,
            this.menuDeleteWithFile});
            this.lvHistoryMenu.Name = "lvHistoryMenu";
            this.lvHistoryMenu.Size = new System.Drawing.Size(181, 92);
            this.lvHistoryMenu.Opening += new System.ComponentModel.CancelEventHandler(this.LvHistoryMenu_Opening);
            // 
            // menuOpenFile
            // 
            this.menuOpenFile.Name = "menuOpenFile";
            this.menuOpenFile.Size = new System.Drawing.Size(180, 22);
            this.menuOpenFile.Text = "打开文件";
            this.menuOpenFile.Click += new System.EventHandler(this.MenuOpenFile_Click);
            // 
            // menuOpenFolder
            // 
            this.menuOpenFolder.Name = "menuOpenFolder";
            this.menuOpenFolder.Size = new System.Drawing.Size(180, 22);
            this.menuOpenFolder.Text = "打开所在文件夹";
            this.menuOpenFolder.Click += new System.EventHandler(this.MenuOpenFolder_Click);
            // 
            // menuCopyContent
            // 
            this.menuCopyContent.Name = "menuCopyContent";
            this.menuCopyContent.Size = new System.Drawing.Size(180, 22);
            this.menuCopyContent.Text = "复制内容";
            this.menuCopyContent.Click += new System.EventHandler(this.MenuCopyContent_Click);
            // 
            // menuViewDetail
            // 
            this.menuViewDetail.Name = "menuViewDetail";
            this.menuViewDetail.Size = new System.Drawing.Size(180, 22);
            this.menuViewDetail.Text = "查看详情";
            this.menuViewDetail.Click += new System.EventHandler(this.MenuViewDetail_Click);
            // 
            // menuSeparator1
            // 
            this.menuSeparator1.Name = "menuSeparator1";
            this.menuSeparator1.Size = new System.Drawing.Size(177, 6);
            // 
            // menuCancelTransfer
            // 
            this.menuCancelTransfer.Name = "menuCancelTransfer";
            this.menuCancelTransfer.Size = new System.Drawing.Size(180, 22);
            this.menuCancelTransfer.Text = "取消传输";
            this.menuCancelTransfer.Click += new System.EventHandler(this.MenuCancelTransfer_Click);
            // 
            // menuSeparator2
            // 
            this.menuSeparator2.Name = "menuSeparator2";
            this.menuSeparator2.Size = new System.Drawing.Size(177, 6);
            // 
            // menuDeleteRecord
            // 
            this.menuDeleteRecord.Name = "menuDeleteRecord";
            this.menuDeleteRecord.Size = new System.Drawing.Size(180, 22);
            this.menuDeleteRecord.Text = "删除记录";
            this.menuDeleteRecord.Click += new System.EventHandler(this.MenuDeleteRecord_Click);
            // 
            // menuDeleteWithFile
            // 
            this.menuDeleteWithFile.Name = "menuDeleteWithFile";
            this.menuDeleteWithFile.Size = new System.Drawing.Size(180, 22);
            this.menuDeleteWithFile.Text = "删除记录 + 文件";
            this.menuDeleteWithFile.Click += new System.EventHandler(this.MenuDeleteWithFile_Click);
            // 
            // MainForm
            // 
            this.AutoScaleDimensions = new System.Drawing.SizeF(6F, 12F);
            this.AutoScaleMode = System.Windows.Forms.AutoScaleMode.Font;
            this.ClientSize = new System.Drawing.Size(784, 411);
            this.Controls.Add(this.lvHistory);
            this.Controls.Add(this.btnOpenDir);
            this.Controls.Add(this.btnSettings);
            this.Controls.Add(this.btnDevices);
            this.Controls.Add(this.btnMinimize);
            this.Controls.Add(this.statusStrip);
            this.MinimumSize = new System.Drawing.Size(400, 300);
            this.Name = "MainForm";
            this.Text = "ShareToPcReciver";
            this.FormClosing += new System.Windows.Forms.FormClosingEventHandler(this.MainForm_FormClosing);
            this.Load += new System.EventHandler(this.MainForm_Load);
            this.ResumeLayout(false);
            this.PerformLayout();
        }

        #endregion

        private System.Windows.Forms.StatusStrip statusStrip;
        private System.Windows.Forms.ToolStripStatusLabel toolStripStatusLabel1;
        private System.Windows.Forms.ToolStripStatusLabel lblStatus;
        private System.Windows.Forms.ToolStripStatusLabel lblPort;
        private System.Windows.Forms.ToolStripStatusLabel lblIp;
        private System.Windows.Forms.ToolStripButton btnCopyIp;
        private System.Windows.Forms.ColumnHeader columnHeader1;
        private System.Windows.Forms.ColumnHeader columnHeader2;
        private System.Windows.Forms.ColumnHeader columnHeader3;
        private System.Windows.Forms.ColumnHeader columnHeader6;
        private System.Windows.Forms.ColumnHeader columnHeader7;
        private System.Windows.Forms.ColumnHeader columnHeader4;
        private System.Windows.Forms.ColumnHeader columnHeader5;
        private System.Windows.Forms.Button btnOpenDir;
        private System.Windows.Forms.Button btnDevices;
        private System.Windows.Forms.Button btnSettings;
        private System.Windows.Forms.Button btnMinimize;
        private System.Windows.Forms.NotifyIcon notifyIcon;
        private System.Windows.Forms.ContextMenuStrip contextMenuStrip;
        private System.Windows.Forms.ToolStripMenuItem toolStripMenuItemOpen;
        private System.Windows.Forms.ToolStripMenuItem toolStripMenuItemExit;
        private System.Windows.Forms.SaveFileDialog saveFileDialog1;
        private System.Windows.Forms.FolderBrowserDialog folderBrowserDialog1;
        private System.Windows.Forms.ListView lvHistory;
        private System.Windows.Forms.ContextMenuStrip lvHistoryMenu;
        private System.Windows.Forms.ToolStripMenuItem menuOpenFile;
        private System.Windows.Forms.ToolStripMenuItem menuOpenFolder;
        private System.Windows.Forms.ToolStripMenuItem menuCopyContent;
        private System.Windows.Forms.ToolStripMenuItem menuViewDetail;
        private System.Windows.Forms.ToolStripSeparator menuSeparator1;
        private System.Windows.Forms.ToolStripMenuItem menuCancelTransfer;
        private System.Windows.Forms.ToolStripSeparator menuSeparator2;
        private System.Windows.Forms.ToolStripMenuItem menuDeleteRecord;
        private System.Windows.Forms.ToolStripMenuItem menuDeleteWithFile;
    }
}