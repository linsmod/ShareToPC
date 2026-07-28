using System;
using System.Runtime.InteropServices;
using System.Threading;
using System.Windows.Forms;

namespace ShareToPcReciver
{
    internal static class Program
    {
        private const string AppMutexName = "Global\\ShareToPcReciver_SingleInstance_9F2A4B8C";

        [STAThread]
        static void Main()
        {
            using (var mutex = new Mutex(false, AppMutexName, out bool createdNew))
            {
                if (!createdNew)
                {
                    // 已有实例在运行，尝试把已有窗口拉到前台
                    IntPtr hWnd = FindWindow(null, "ShareToPcReciver");
                    if (hWnd != IntPtr.Zero)
                    {
                        ShowWindow(hWnd, SW_RESTORE);
                        SetForegroundWindow(hWnd);
                    }
                    return;
                }

                Application.EnableVisualStyles();
                Application.SetCompatibleTextRenderingDefault(false);
                Application.Run(new MainForm());
            }
        }

        [DllImport("user32.dll")]
        private static extern bool SetForegroundWindow(IntPtr hWnd);

        [DllImport("user32.dll")]
        private static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);

        [DllImport("user32.dll", CharSet = CharSet.Unicode)]
        private static extern IntPtr FindWindow(string lpClassName, string lpWindowName);

        private const int SW_RESTORE = 9;
    }
}
