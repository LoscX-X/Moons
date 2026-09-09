using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.Diagnostics;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.IO;
using System.IO.Compression;
using System.Net;
using System.Reflection;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;
using System.Windows.Forms;
using Moons.Shared;

[assembly: AssemblyTitle("Moons Loader")]
[assembly: AssemblyDescription("Moons JNI/JVMTI loader with cached UI runtime")]
[assembly: AssemblyCompany("Moons")]
[assembly: AssemblyProduct("Moons Loader")]
[assembly: AssemblyVersion("1.0.0.0")]
[assembly: AssemblyFileVersion("1.0.0.0")]

namespace Moons.WindowsLauncher
{
    internal static class Program
    {
        private const string DefaultDisplayName = "Moons";
        private static readonly string DisplayName = ResolveDisplayName();
        private static readonly Color WindowBackground = Color.FromArgb(24, 24, 24);
        private static readonly Color Surface = Color.FromArgb(20, 20, 22);
        private static readonly Color SurfaceHover = Color.FromArgb(32, 31, 37);
        private static readonly Color Border = Color.FromArgb(45, 44, 50);
        private static readonly Color Muted = Color.FromArgb(139, 143, 148);
        private static readonly Color Accent = Color.FromArgb(145, 116, 255);
        private static readonly Color AccentHover = Color.FromArgb(161, 137, 255);
        private const string Payload26_1Resource = "Moons.Payload.26_1.jar";
        private const string Payload26_2PatchResource = "Moons.Payload.26_2.patch";
        private const string Payload26_3PatchResource = "Moons.Payload.26_3.patch";
        private const string FeaturesJarEntry =
            "META-INF/moons/modules/moons-core-features.jar";
        private const string BootstrapApiResource = "Moons.Api.jar";
        private const string BridgeResource = "Moons.Bridge.dll";
        private const string UiRuntimeMetadataResource = "Moons.UiRuntime.properties";
        private const string UiRuntimeResource = "Moons.UiRuntime.jar";

        [DllImport("dwmapi.dll")]
        private static extern int DwmSetWindowAttribute(
            IntPtr window, int attribute, ref int value, int valueSize);

        [DllImport("user32.dll")]
        private static extern bool ReleaseCapture();

        [DllImport("user32.dll")]
        private static extern IntPtr SendMessage(
            IntPtr window, int message, IntPtr wParam, IntPtr lParam);

        private delegate bool EnumWindowsCallback(IntPtr window, IntPtr parameter);

        [DllImport("user32.dll")]
        private static extern bool EnumWindows(
            EnumWindowsCallback callback, IntPtr parameter);

        [DllImport("user32.dll")]
        private static extern bool IsWindowVisible(IntPtr window);

        [DllImport("user32.dll")]
        private static extern int GetWindowTextLength(IntPtr window);

        [DllImport("user32.dll", CharSet = CharSet.Unicode)]
        private static extern int GetWindowText(
            IntPtr window, StringBuilder text, int maximumCount);

        [DllImport("user32.dll")]
        private static extern uint GetWindowThreadProcessId(
            IntPtr window, out uint processId);

        [DllImport("gdi32.dll")]
        private static extern int GetDeviceCaps(IntPtr deviceContext, int index);

        [DllImport("winmm.dll", EntryPoint = "timeBeginPeriod")]
        private static extern uint TimeBeginPeriod(uint period);

        [DllImport("winmm.dll", EntryPoint = "timeEndPeriod")]
        private static extern uint TimeEndPeriod(uint period);

        [DllImport("kernel32.dll", SetLastError = true)]
        private static extern IntPtr OpenProcess(uint access, bool inherit, int processId);

        [DllImport("kernel32.dll", SetLastError = true)]
        private static extern uint GetProcessId(IntPtr process);

        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        private static extern bool QueryFullProcessImageName(
            IntPtr process, uint flags, StringBuilder image, ref int size);

        [DllImport("kernel32.dll", SetLastError = true)]
        private static extern bool IsWow64Process2(
            IntPtr process, out ushort processMachine, out ushort nativeMachine);

        [DllImport("kernel32.dll", SetLastError = true)]
        private static extern IntPtr VirtualAllocEx(
            IntPtr process, IntPtr address, UIntPtr size, uint allocationType, uint protect);

        [DllImport("kernel32.dll", SetLastError = true)]
        private static extern bool WriteProcessMemory(
            IntPtr process, IntPtr address, byte[] buffer, UIntPtr size, out UIntPtr written);

        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        private static extern IntPtr GetModuleHandle(string moduleName);

        [DllImport("kernel32.dll", CharSet = CharSet.Ansi, SetLastError = true)]
        private static extern IntPtr GetProcAddress(IntPtr module, string procedureName);

        [DllImport("kernel32.dll", SetLastError = true)]
        private static extern IntPtr CreateRemoteThread(
            IntPtr process, IntPtr attributes, UIntPtr stackSize,
            IntPtr startAddress, IntPtr parameter, uint flags, out uint threadId);

        [DllImport("kernel32.dll", SetLastError = true)]
        private static extern uint WaitForSingleObject(IntPtr handle, uint milliseconds);

        [DllImport("kernel32.dll", SetLastError = true)]
        private static extern bool GetExitCodeThread(IntPtr thread, out uint exitCode);

        [DllImport("kernel32.dll", SetLastError = true)]
        private static extern bool VirtualFreeEx(
            IntPtr process, IntPtr address, UIntPtr size, uint freeType);

        [DllImport("kernel32.dll", SetLastError = true)]
        private static extern bool CloseHandle(IntPtr handle);

        [STAThread]
        private static int Main(string[] arguments)
        {
            if (Contains(arguments, "--self-test-version-detection"))
            {
                return SelfTestVersionDetection();
            }
            if (Contains(arguments, "--extract-only"))
            {
                try
                {
                    string home = ResolveHome();
                    EnsureUiRuntime(home, arguments, null, null, delegate { return false; });
                    ExtractPayload(home, "26.1");
                    ExtractPayload(home, "26.2");
                    ExtractPayload(home, "26.3");
                    ExtractBootstrapApi(home);
                    ExtractBridge(home);
                    HardwareIdGenerator.Generate();
                    return 0;
                }
                catch (Exception error)
                {
                    MessageBox.Show(error.Message, DisplayName + " Loader",
                        MessageBoxButtons.OK, MessageBoxIcon.Error);
                    return 1;
                }
            }

            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);
            string snapshotPath = OptionArgument(arguments, "--self-test-ui-snapshot");
            if (!String.IsNullOrWhiteSpace(snapshotPath))
            {
                return SaveLoaderSnapshot(snapshotPath);
            }
            LoaderForm form = new LoaderForm(arguments);
            Application.Run(form);
            return form.ExitCode;
        }

        private static int SaveLoaderSnapshot(string outputPath)
        {
            try
            {
                string fullPath = Path.GetFullPath(outputPath);
                string directory = Path.GetDirectoryName(fullPath);
                if (!String.IsNullOrEmpty(directory)) Directory.CreateDirectory(directory);
                using (LoaderForm form = new LoaderForm(new string[0], false))
                {
                    form.ShowInTaskbar = false;
                    form.StartPosition = FormStartPosition.Manual;
                    form.Location = new Point(-32000, -32000);
                    form.Show();
                    Application.DoEvents();
                    using (Bitmap bitmap = new Bitmap(form.Width, form.Height))
                    {
                        form.DrawToBitmap(bitmap, new Rectangle(Point.Empty, bitmap.Size));
                        bitmap.Save(fullPath, System.Drawing.Imaging.ImageFormat.Png);
                    }
                    form.Hide();
                }
                return 0;
            }
            catch (Exception error)
            {
                Console.Error.WriteLine(error);
                return 4;
            }
        }

        private static int SelfTestVersionDetection()
        {
            bool passed = true;
            passed &= String.Equals(MatchSupportedVersion(
                "net.minecraft.client.main.Main --version 26.1.2"),
                "26.1", StringComparison.Ordinal);
            passed &= String.Equals(MatchSupportedVersion(
                @"C:\Games\Minecraft\versions\26.2\client.jar"),
                "26.2", StringComparison.Ordinal);
            passed &= String.Equals(NormalizeConfiguredVersion("26.1"),
                "26.1", StringComparison.Ordinal);
            passed &= String.Equals(NormalizeConfiguredVersion("26.1.2"),
                "26.1", StringComparison.Ordinal);
            passed &= NormalizeConfiguredVersion("26.1.3") == null;
            passed &= String.Equals(NormalizeConfiguredVersion("26.3-pre-3"),
                "26.3", StringComparison.Ordinal);
            passed &= String.Equals(MatchSupportedVersion(
                "net.minecraft.client.main.Main --version 26.3-pre-3"),
                "26.3", StringComparison.Ordinal);
            passed &= String.Equals(MatchSupportedVersion(
                @"C:\Games\Minecraft\versions\26.3-pre-3\26.3-pre-3.jar"),
                "26.3", StringComparison.Ordinal);
            passed &= NormalizeConfiguredVersion("26.3-pre-2") == null;
            passed &= MatchSupportedVersion("26.3-pre-30") == null;
            passed &= MatchSupportedVersion("26.3-pre-3-custom") == null;
            passed &= MatchSupportedVersion("26.3") == null;
            passed &= MatchSupportedVersion("26.2 and 26.3-pre-3") == null;
            passed &= MatchSupportedVersion("Minecraft 1.21.5") == null;
            passed &= MatchSupportedVersion("26.1.2 and 26.2") == null;
            passed &= IsMinecraftTargetEvidence(
                @"C:\Users\player\.lunarclient\jre\runtime\bin\javaw.exe", "", "");
            passed &= IsMinecraftTargetEvidence(
                @"C:\Program Files\Java\jdk-25\bin\java.exe", "",
                "net.minecraft.client.main.Main --version 26.1.2");
            passed &= !IsMinecraftTargetEvidence(
                @"C:\Program Files\Java\jdk-25\bin\java.exe", "", "");
            return passed ? 0 : 3;
        }

        private static void UseRoundedCorners(Form form)
        {
            try
            {
                const int DwmWindowCornerPreference = 33;
                const int Round = 2;
                int preference = Round;
                DwmSetWindowAttribute(form.Handle, DwmWindowCornerPreference,
                    ref preference, sizeof(int));
            }
            catch
            {
                // Older Windows versions do not expose DWM corner preferences.
            }
        }

        private static void EnableWindowDrag(Form form, Control surface)
        {
            surface.MouseDown += delegate(object sender, MouseEventArgs eventArgs)
            {
                if (eventArgs.Button != MouseButtons.Left) return;
                ReleaseCapture();
                SendMessage(form.Handle, 0x00A1, new IntPtr(2), IntPtr.Zero);
            };
        }

        private static void InstallWindowChrome(Form form, bool allowMinimize)
        {
            WindowChromeButton close = new WindowChromeButton(true);
            close.Left = form.ClientSize.Width - 48;
            close.Top = 10;
            close.Anchor = AnchorStyles.Top | AnchorStyles.Right;
            close.Click += delegate { form.Close(); };
            form.Controls.Add(close);

            if (allowMinimize)
            {
                WindowChromeButton minimize = new WindowChromeButton(false);
                minimize.Left = form.ClientSize.Width - 88;
                minimize.Top = 10;
                minimize.Anchor = AnchorStyles.Top | AnchorStyles.Right;
                minimize.Click += delegate { form.WindowState = FormWindowState.Minimized; };
                form.Controls.Add(minimize);
                minimize.BringToFront();
            }
            close.BringToFront();
            EnableWindowDrag(form, form);
        }

        private static void StyleButton(Button button, bool primary)
        {
            button.BackColor = primary ? Accent : Surface;
            button.ForeColor = Color.White;
            button.FlatStyle = FlatStyle.Flat;
            button.FlatAppearance.BorderSize = 1;
            button.FlatAppearance.BorderColor = primary ? Accent : Border;
            button.FlatAppearance.MouseOverBackColor = primary ? AccentHover : SurfaceHover;
            button.FlatAppearance.MouseDownBackColor = primary
                ? Color.FromArgb(126, 96, 232) : Color.FromArgb(38, 43, 59);
            button.Cursor = Cursors.Hand;
            button.Font = new Font("Segoe UI Semibold", 9.0F, FontStyle.Bold);
            button.UseVisualStyleBackColor = false;
        }

        private static GraphicsPath RoundedRectangle(Rectangle bounds, int radius)
        {
            int diameter = Math.Max(2, radius * 2);
            GraphicsPath path = new GraphicsPath();
            path.AddArc(bounds.Left, bounds.Top, diameter, diameter, 180, 90);
            path.AddArc(bounds.Right - diameter, bounds.Top, diameter, diameter, 270, 90);
            path.AddArc(bounds.Right - diameter, bounds.Bottom - diameter, diameter, diameter, 0, 90);
            path.AddArc(bounds.Left, bounds.Bottom - diameter, diameter, diameter, 90, 90);
            path.CloseFigure();
            return path;
        }

        private sealed class WindowChromeButton : Control
        {
            private readonly bool closeButton;
            private bool hovered;
            private bool pressed;

            internal WindowChromeButton(bool closeButton)
            {
                this.closeButton = closeButton;
                Size = new Size(36, 32);
                Cursor = Cursors.Hand;
                TabStop = false;
                AccessibleName = closeButton ? "Close" : "Minimize";
                DoubleBuffered = true;
                SetStyle(ControlStyles.AllPaintingInWmPaint
                    | ControlStyles.OptimizedDoubleBuffer
                    | ControlStyles.SupportsTransparentBackColor
                    | ControlStyles.UserPaint, true);
                BackColor = Color.Transparent;
            }

            protected override void OnMouseEnter(EventArgs eventArgs)
            {
                hovered = true;
                Invalidate();
                base.OnMouseEnter(eventArgs);
            }

            protected override void OnMouseLeave(EventArgs eventArgs)
            {
                hovered = false;
                pressed = false;
                Invalidate();
                base.OnMouseLeave(eventArgs);
            }

            protected override void OnMouseDown(MouseEventArgs eventArgs)
            {
                if (eventArgs.Button == MouseButtons.Left)
                {
                    pressed = true;
                    Invalidate();
                }
                base.OnMouseDown(eventArgs);
            }

            protected override void OnMouseUp(MouseEventArgs eventArgs)
            {
                pressed = false;
                Invalidate();
                base.OnMouseUp(eventArgs);
            }

            protected override void OnPaint(PaintEventArgs eventArgs)
            {
                eventArgs.Graphics.SmoothingMode = SmoothingMode.AntiAlias;
                if (hovered || pressed)
                {
                    Color background = closeButton && hovered
                        ? Color.FromArgb(54, 34, 42)
                        : pressed ? Color.FromArgb(43, 43, 47)
                        : Color.FromArgb(34, 34, 37);
                    using (GraphicsPath path = RoundedRectangle(
                        new Rectangle(0, 0, Width - 1, Height - 1), 10))
                    using (SolidBrush fill = new SolidBrush(background))
                    {
                        eventArgs.Graphics.FillPath(fill, path);
                    }
                }

                Color glyph = hovered ? Color.White : Color.FromArgb(116, 118, 124);
                using (Pen pen = new Pen(glyph, 1.7F))
                {
                    pen.StartCap = LineCap.Round;
                    pen.EndCap = LineCap.Round;
                    if (closeButton)
                    {
                        eventArgs.Graphics.DrawLine(pen, 14, 12, 22, 20);
                        eventArgs.Graphics.DrawLine(pen, 22, 12, 14, 20);
                    }
                    else
                    {
                        eventArgs.Graphics.DrawLine(pen, 14, 18, 22, 18);
                    }
                }
            }
        }

        private sealed class SurfacePanel : Panel
        {
            internal int Radius = 12;

            internal SurfacePanel()
            {
                DoubleBuffered = true;
                SetStyle(ControlStyles.SupportsTransparentBackColor, true);
                BackColor = Color.Transparent;
            }

            protected override void OnPaintBackground(PaintEventArgs eventArgs)
            {
                eventArgs.Graphics.SmoothingMode = SmoothingMode.AntiAlias;
                Rectangle bounds = new Rectangle(0, 0, Width - 1, Height - 1);
                using (GraphicsPath path = RoundedRectangle(bounds, Radius))
                using (SolidBrush fill = new SolidBrush(Surface))
                using (Pen outline = new Pen(Border))
                {
                    eventArgs.Graphics.FillPath(fill, path);
                    eventArgs.Graphics.DrawPath(outline, path);
                }
            }
        }

        private sealed class AccentProgressBar : Control
        {
            private double value;

            internal double Value
            {
                get { return value; }
                set
                {
                    this.value = Math.Max(0.0, Math.Min(100.0, value));
                    Invalidate();
                }
            }

            internal AccentProgressBar()
            {
                DoubleBuffered = true;
                SetStyle(ControlStyles.SupportsTransparentBackColor, true);
                Height = 10;
                BackColor = Color.Transparent;
            }

            protected override void OnPaint(PaintEventArgs eventArgs)
            {
                base.OnPaint(eventArgs);
                eventArgs.Graphics.SmoothingMode = SmoothingMode.AntiAlias;
                Rectangle track = new Rectangle(0, 0, Width - 1, Height - 1);
                using (GraphicsPath trackPath = RoundedRectangle(track, Height / 2))
                using (SolidBrush trackBrush = new SolidBrush(Color.FromArgb(13, 13, 13)))
                {
                    eventArgs.Graphics.FillPath(trackBrush, trackPath);
                }
                int fillWidth = (int)Math.Round((Width - 1) * value / 100.0);
                if (fillWidth < 2) return;
                Rectangle fill = new Rectangle(0, 0, fillWidth, Height - 1);
                using (GraphicsPath fillPath = RoundedRectangle(fill, Height / 2))
                using (SolidBrush fillBrush = new SolidBrush(Accent))
                {
                    eventArgs.Graphics.FillPath(fillBrush, fillPath);
                }
            }
        }

        private static void Execute(
            string[] arguments,
            Action<int, string> progress,
            Action<int, string> downloadProgress,
            Func<IList<MinecraftTarget>, MinecraftTarget> chooseTarget,
            Func<bool> cancelled)
        {
            string home = ResolveHome();
            progress(4, "Checking UI runtime dependencies");
            EnsureUiRuntime(home, arguments, progress, downloadProgress, cancelled);
            progress(18, "Extracting shared " + DisplayName + " JVMTI components");
            string hardwareId = HardwareIdGenerator.Generate();
            string bootstrapApi = ExtractBootstrapApi(home);
            string bridge = ExtractBridge(home);
            ThrowIfCancelled(cancelled);

            string explicitPid = PidArgument(arguments);
            MinecraftTarget target;
            if (explicitPid != null)
            {
                target = new MinecraftTarget(explicitPid, "PID " + explicitPid);
            }
            else
            {
                progress(27, "Waiting for a Minecraft client");
                while (true)
                {
                    ThrowIfCancelled(cancelled);
                    IList<MinecraftTarget> targets = DiscoverTargets();
                    if (targets.Count == 1)
                    {
                        target = targets[0];
                        break;
                    }
                    if (targets.Count > 1)
                    {
                        target = chooseTarget(targets);
                        if (target == null)
                        {
                            throw new OperationCanceledException();
                        }
                        break;
                    }
                    if (Contains(arguments, "--no-wait"))
                    {
                        throw new InvalidOperationException("No Minecraft JVM was found.");
                    }
                    Thread.Sleep(1000);
                }
            }

            string detectedVersion = DetectMinecraftVersion(
                target, OptionArgument(arguments, "--minecraft-version"));
            progress(43, "Minecraft " + detectedVersion + " selected: " + target.Label);
            string payload = ExtractPayload(home, detectedVersion);
            progress(48, "Loaded embedded Minecraft " + detectedVersion + " payload");
            LoadBridge(bridge, payload, bootstrapApi, home, hardwareId,
                target.Pid, progress, cancelled);
        }

        private sealed class LoaderForm : Form
        {
            private static readonly PointF[] Stars =
            {
                new PointF(0.07F, 0.18F), new PointF(0.15F, 0.72F),
                new PointF(0.23F, 0.31F), new PointF(0.31F, 0.84F),
                new PointF(0.39F, 0.13F), new PointF(0.47F, 0.67F),
                new PointF(0.56F, 0.24F), new PointF(0.64F, 0.79F),
                new PointF(0.72F, 0.39F), new PointF(0.81F, 0.16F),
                new PointF(0.88F, 0.62F), new PointF(0.94F, 0.31F),
                new PointF(0.11F, 0.47F), new PointF(0.76F, 0.91F)
            };

            private readonly string[] arguments;
            private readonly BackgroundWorker worker;
            private readonly Label status;
            private readonly AccentProgressBar progressTrack;
            private readonly Label downloadStatus;
            private readonly AccentProgressBar downloadTrack;
            private readonly System.Windows.Forms.Timer progressTimer;
            private readonly Stopwatch progressClock;
            private readonly SolidBrush starBrush = new SolidBrush(Color.White);
            private double displayedProgress;
            private double lastAnimationSeconds;
            private double starAnimationSeconds;
            private int targetProgress;
            private bool downloadCompleted;
            private RunWorkerCompletedEventArgs pendingCompletion;
            private bool allowClose;
            private bool highResolutionTimer;

            internal int ExitCode { get; private set; }

            internal LoaderForm(string[] arguments) : this(arguments, true)
            {
            }

            internal LoaderForm(string[] arguments, bool autoStart)
            {
                this.arguments = arguments;
                Text = DisplayName + " Loader";
                ClientSize = new Size(640, 360);
                BackColor = WindowBackground;
                ForeColor = Color.White;
                Font = new Font("Segoe UI", 9.0F);
                FormBorderStyle = FormBorderStyle.None;
                ShowIcon = false;
                ControlBox = false;
                MaximizeBox = false;
                MinimizeBox = false;
                StartPosition = FormStartPosition.CenterScreen;
                DoubleBuffered = true;
                UseRoundedCorners(this);

                Label title = new Label();
                title.Text = DisplayName.ToUpperInvariant();
                title.AutoSize = true;
                title.Top = 106;
                title.ForeColor = Color.White;
                title.Font = new Font("Segoe UI Semibold", 18.0F, FontStyle.Bold);
                title.Left = (ClientSize.Width - title.PreferredWidth) / 2;
                Controls.Add(title);

                Label subtitle = new Label();
                subtitle.Text = "CLIENT LOADER";
                subtitle.AutoSize = true;
                subtitle.Top = 139;
                subtitle.ForeColor = Muted;
                subtitle.Font = new Font("Segoe UI Semibold", 7.5F, FontStyle.Bold);
                subtitle.Left = (ClientSize.Width - subtitle.PreferredWidth) / 2;
                Controls.Add(subtitle);

                status = new Label();
                status.Text = "Preparing...";
                status.AutoEllipsis = true;
                status.TextAlign = ContentAlignment.MiddleCenter;
                status.Left = (ClientSize.Width - 340) / 2;
                status.Top = 218;
                status.Width = 340;
                status.Height = 24;
                status.ForeColor = Muted;
                status.Font = new Font("Segoe UI", 8.5F);
                status.BackColor = Color.Transparent;
                Controls.Add(status);

                progressTrack = new AccentProgressBar();
                progressTrack.Left = (ClientSize.Width - 320) / 2;
                progressTrack.Top = 196;
                progressTrack.Width = 320;
                progressTrack.Height = 8;
                Controls.Add(progressTrack);

                downloadTrack = new AccentProgressBar();
                downloadTrack.Left = progressTrack.Left;
                downloadTrack.Top = 218;
                downloadTrack.Width = progressTrack.Width;
                downloadTrack.Height = 6;
                downloadTrack.Visible = false;
                Controls.Add(downloadTrack);

                downloadStatus = new Label();
                downloadStatus.AutoEllipsis = true;
                downloadStatus.TextAlign = ContentAlignment.MiddleCenter;
                downloadStatus.Left = progressTrack.Left;
                downloadStatus.Top = 228;
                downloadStatus.Width = progressTrack.Width;
                downloadStatus.Height = 18;
                downloadStatus.ForeColor = Muted;
                downloadStatus.Font = new Font("Segoe UI", 7.5F);
                downloadStatus.BackColor = Color.Transparent;
                downloadStatus.Visible = false;
                Controls.Add(downloadStatus);
                InstallWindowChrome(this, true);

                worker = new BackgroundWorker();
                worker.WorkerReportsProgress = true;
                worker.WorkerSupportsCancellation = true;
                worker.DoWork += Work;
                worker.ProgressChanged += ProgressChanged;
                worker.RunWorkerCompleted += Completed;
                progressTimer = new System.Windows.Forms.Timer();
                progressTimer.Interval = 16;
                progressTimer.Tick += AnimateProgress;
                progressClock = Stopwatch.StartNew();
                progressTimer.Start();
                Shown += delegate
                {
                    MatchAnimationRateToDisplay();
                    if (autoStart) worker.RunWorkerAsync();
                };
                FormClosed += delegate
                {
                    progressTimer.Stop();
                    progressTimer.Dispose();
                    if (highResolutionTimer) TimeEndPeriod(1);
                };
                FormClosing += HandleFormClosing;
            }

            private void MatchAnimationRateToDisplay()
            {
                const int VerticalRefresh = 116;
                int refreshRate = 60;
                using (Graphics graphics = CreateGraphics())
                {
                    IntPtr deviceContext = graphics.GetHdc();
                    try
                    {
                        int detected = GetDeviceCaps(deviceContext, VerticalRefresh);
                        if (detected >= 30 && detected <= 360) refreshRate = detected;
                    }
                    finally
                    {
                        graphics.ReleaseHdc(deviceContext);
                    }
                }

                progressTimer.Interval = Math.Max(4,
                    (int)Math.Round(1000.0 / refreshRate));
                highResolutionTimer = TimeBeginPeriod(1) == 0;
                progressClock.Restart();
                lastAnimationSeconds = 0.0;
            }

            private void Work(object sender, DoWorkEventArgs eventArgs)
            {
                try
                {
                    Execute(
                        arguments,
                        delegate(int value, string message)
                        {
                            worker.ReportProgress(value, message);
                        },
                        delegate(int value, string message)
                        {
                            worker.ReportProgress(value,
                                new DownloadProgressState(value, message));
                        },
                        SelectTarget,
                        delegate { return worker.CancellationPending; });
                }
                catch (OperationCanceledException)
                {
                    eventArgs.Cancel = true;
                }
            }

            private void ProgressChanged(object sender, ProgressChangedEventArgs eventArgs)
            {
                DownloadProgressState download = eventArgs.UserState as DownloadProgressState;
                if (download != null)
                {
                    downloadTrack.Visible = true;
                    downloadStatus.Visible = true;
                    status.Top = 252;
                    downloadTrack.Value = download.Percentage;
                    downloadStatus.Text = download.Message;
                    downloadCompleted = download.Percentage >= 100;
                    return;
                }

                if (downloadCompleted)
                {
                    downloadTrack.Visible = false;
                    downloadStatus.Visible = false;
                    status.Top = 218;
                    downloadCompleted = false;
                }
                int value = Math.Max(0, Math.Min(100, eventArgs.ProgressPercentage));
                targetProgress = Math.Max(targetProgress, value);
                status.Text = eventArgs.UserState == null
                    ? "Working..." : eventArgs.UserState.ToString();
            }

            private sealed class DownloadProgressState
            {
                internal readonly int Percentage;
                internal readonly string Message;

                internal DownloadProgressState(int percentage, string message)
                {
                    Percentage = Math.Max(0, Math.Min(100, percentage));
                    Message = message;
                }
            }

            private void AnimateProgress(object sender, EventArgs eventArgs)
            {
                double now = progressClock.Elapsed.TotalSeconds;
                double deltaSeconds = now - lastAnimationSeconds;
                lastAnimationSeconds = now;
                starAnimationSeconds = now;
                Invalidate(false);
                if (deltaSeconds <= 0.0 || deltaSeconds > 0.25)
                {
                    deltaSeconds = progressTimer.Interval / 1000.0;
                }

                if (displayedProgress < targetProgress)
                {
                    double remaining = targetProgress - displayedProgress;
                    double blend = 1.0 - Math.Exp(-14.0 * deltaSeconds);
                    displayedProgress += remaining * blend;
                    if (targetProgress - displayedProgress < 0.05)
                    {
                        displayedProgress = targetProgress;
                    }
                    RenderProgress();
                }

                if (pendingCompletion != null && displayedProgress >= 99.999)
                {
                    RunWorkerCompletedEventArgs result = pendingCompletion;
                    pendingCompletion = null;
                    FinishCompletion(result);
                }
            }

            private void RenderProgress()
            {
                progressTrack.Value = displayedProgress;
            }

            private void Completed(object sender, RunWorkerCompletedEventArgs eventArgs)
            {
                if (!eventArgs.Cancelled && eventArgs.Error == null)
                {
                    pendingCompletion = eventArgs;
                    targetProgress = 100;
                    status.Text = "Finalizing load";
                    if (displayedProgress < 99.999)
                    {
                        return;
                    }
                    pendingCompletion = null;
                }
                FinishCompletion(eventArgs);
            }

            private void FinishCompletion(RunWorkerCompletedEventArgs eventArgs)
            {
                allowClose = true;
                progressTimer.Stop();
                if (eventArgs.Cancelled)
                {
                    ExitCode = 2;
                    Close();
                    return;
                }
                if (eventArgs.Error != null)
                {
                    ExitCode = 1;
                    status.Text = "Load failed";
                    MessageBox.Show(this, eventArgs.Error.Message, DisplayName + " Loader",
                        MessageBoxButtons.OK, MessageBoxIcon.Error);
                    Close();
                    return;
                }

                ExitCode = 0;
                displayedProgress = 100.0;
                targetProgress = 100;
                RenderProgress();
                status.Text = "Load completed";
                if (!Program.Contains(arguments, "--no-success-dialog"))
                {
                    string message = DisplayName + " was loaded successfully.";
                    MessageBox.Show(this, message,
                        DisplayName + " Loader",
                        MessageBoxButtons.OK, MessageBoxIcon.Information);
                }
                Close();
            }

            private MinecraftTarget SelectTarget(IList<MinecraftTarget> targets)
            {
                if (InvokeRequired)
                {
                    return (MinecraftTarget)Invoke(
                        new Func<IList<MinecraftTarget>, MinecraftTarget>(SelectTarget), targets);
                }
                using (TargetDialog dialog = new TargetDialog(targets))
                {
                    // Keep both windows in the normal z-order. A modal owned dialog
                    // is guaranteed to stay above this loader without pinning
                    // either window above unrelated desktop applications.
                    dialog.ShowInTaskbar = false;
                    dialog.Shown += delegate
                    {
                        dialog.BringToFront();
                        dialog.Activate();
                    };
                    return dialog.ShowDialog(this) == DialogResult.OK
                        ? dialog.SelectedTarget : null;
                }
            }

            private void HandleFormClosing(object sender, FormClosingEventArgs eventArgs)
            {
                if (!allowClose && worker.IsBusy)
                {
                    worker.CancelAsync();
                    status.Text = "Cancelling...";
                    eventArgs.Cancel = true;
                }
                else if (!allowClose && pendingCompletion != null)
                {
                    eventArgs.Cancel = true;
                }
            }

            protected override void OnPaint(PaintEventArgs eventArgs)
            {
                base.OnPaint(eventArgs);
                eventArgs.Graphics.SmoothingMode = SmoothingMode.AntiAlias;
                for (int i = 0; i < Stars.Length; i++)
                {
                    double wave = (Math.Sin(starAnimationSeconds * 0.9 + i * 1.73) + 1.0) * 0.5;
                    int alpha = 18 + (int)Math.Round(wave * 48.0);
                    float diameter = i % 5 == 0 ? 2.2F : 1.4F;
                    float x = Stars[i].X * ClientSize.Width;
                    float y = Stars[i].Y * ClientSize.Height;
                    starBrush.Color = Color.FromArgb(alpha, Color.White);
                    eventArgs.Graphics.FillEllipse(starBrush, x, y, diameter, diameter);
                }
            }

            protected override void Dispose(bool disposing)
            {
                if (disposing) starBrush.Dispose();
                base.Dispose(disposing);
            }
        }

        private sealed class TargetDialog : Form
        {
            private readonly ListBox targets;

            internal MinecraftTarget SelectedTarget
            {
                get { return targets.SelectedItem as MinecraftTarget; }
            }

            internal TargetDialog(IList<MinecraftTarget> candidates)
            {
                Text = "Select Minecraft — " + DisplayName;
                ClientSize = new Size(540, 278);
                BackColor = WindowBackground;
                ForeColor = Color.White;
                Font = new Font("Segoe UI", 9.0F);
                FormBorderStyle = FormBorderStyle.None;
                ShowIcon = false;
                ControlBox = false;
                MaximizeBox = false;
                MinimizeBox = false;
                ShowInTaskbar = false;
                StartPosition = FormStartPosition.CenterParent;
                UseRoundedCorners(this);

                Label title = new Label();
                title.Text = "Choose Minecraft";
                title.AutoSize = false;
                title.Left = 24;
                title.Top = 22;
                title.Width = 492;
                title.Height = 30;
                title.TextAlign = ContentAlignment.MiddleCenter;
                title.ForeColor = Color.White;
                title.Font = new Font("Segoe UI Semibold", 14.0F, FontStyle.Bold);
                Controls.Add(title);

                SurfacePanel listCard = new SurfacePanel();
                listCard.Left = 24;
                listCard.Top = 78;
                listCard.Width = 492;
                listCard.Height = 128;
                Controls.Add(listCard);

                targets = new ListBox();
                targets.Left = 10;
                targets.Top = 10;
                targets.Width = 472;
                targets.Height = 108;
                targets.BackColor = Surface;
                targets.ForeColor = Color.White;
                targets.BorderStyle = BorderStyle.None;
                targets.DrawMode = DrawMode.OwnerDrawFixed;
                targets.ItemHeight = 40;
                targets.DrawItem += DrawTarget;
                foreach (MinecraftTarget target in candidates)
                {
                    targets.Items.Add(target);
                }
                if (targets.Items.Count > 0)
                {
                    targets.SelectedIndex = 0;
                }
                targets.DoubleClick += delegate
                {
                    if (targets.SelectedItem != null)
                    {
                        DialogResult = DialogResult.OK;
                        Close();
                    }
                };
                listCard.Controls.Add(targets);

                Button load = new Button();
                load.Text = "Load Moons";
                load.DialogResult = DialogResult.OK;
                load.Left = 364;
                load.Top = 226;
                load.Width = 152;
                load.Height = 34;
                StyleButton(load, true);
                Controls.Add(load);

                Button cancel = new Button();
                cancel.Text = "Cancel";
                cancel.DialogResult = DialogResult.Cancel;
                cancel.Left = 249;
                cancel.Top = 226;
                cancel.Width = 105;
                cancel.Height = 34;
                StyleButton(cancel, false);
                Controls.Add(cancel);

                AcceptButton = load;
                CancelButton = cancel;
                EnableWindowDrag(this, title);
                InstallWindowChrome(this, false);
            }

            private void DrawTarget(object sender, DrawItemEventArgs eventArgs)
            {
                if (eventArgs.Index < 0 || eventArgs.Index >= targets.Items.Count) return;
                bool selected = (eventArgs.State & DrawItemState.Selected) != 0;
                Rectangle bounds = eventArgs.Bounds;
                Color background = selected ? Color.FromArgb(42, 37, 64) : Surface;
                using (SolidBrush fill = new SolidBrush(background))
                {
                    eventArgs.Graphics.FillRectangle(fill, bounds);
                }
                if (selected)
                {
                    using (SolidBrush accent = new SolidBrush(Accent))
                    {
                        eventArgs.Graphics.FillRectangle(accent,
                            bounds.Left, bounds.Top + 6, 3, bounds.Height - 12);
                    }
                }

                MinecraftTarget target = targets.Items[eventArgs.Index] as MinecraftTarget;
                string label = target == null ? targets.Items[eventArgs.Index].ToString() : target.Label;
                eventArgs.Graphics.SmoothingMode = SmoothingMode.AntiAlias;
                using (SolidBrush icon = new SolidBrush(selected
                    ? Color.FromArgb(70, 57, 112) : Color.FromArgb(35, 39, 53)))
                using (SolidBrush dot = new SolidBrush(Accent))
                {
                    eventArgs.Graphics.FillEllipse(icon, bounds.Left + 12, bounds.Top + 7, 26, 26);
                    eventArgs.Graphics.FillEllipse(dot, bounds.Left + 22, bounds.Top + 17, 6, 6);
                }
                TextRenderer.DrawText(eventArgs.Graphics, label, targets.Font,
                    new Rectangle(bounds.Left + 50, bounds.Top + 8, bounds.Width - 62, 24),
                    Color.White, TextFormatFlags.EndEllipsis | TextFormatFlags.VerticalCenter);
                eventArgs.DrawFocusRectangle();
            }
        }

        private sealed class MinecraftTarget
        {
            internal readonly string Pid;
            internal readonly string Label;

            internal MinecraftTarget(string pid, string label)
            {
                Pid = pid;
                Label = label;
            }

            public override string ToString()
            {
                return Label;
            }
        }

        private static void ThrowIfCancelled(Func<bool> cancelled)
        {
            if (cancelled())
            {
                throw new OperationCanceledException();
            }
        }

        private static string PidArgument(string[] arguments)
        {
            return OptionArgument(arguments, "--pid");
        }

        private static string DetectMinecraftVersion(MinecraftTarget target, string configured)
        {
            if (!String.IsNullOrWhiteSpace(configured))
            {
                string selected = NormalizeConfiguredVersion(configured);
                if (selected == null)
                {
                    throw new InvalidOperationException(
                        "Unsupported --minecraft-version value: " + configured
                        + ". Expected 26.1, 26.1.2, 26.2, or 26.3-pre-3.");
                }
                return selected;
            }

            int pid;
            if (!Int32.TryParse(target.Pid, out pid))
            {
                throw new InvalidOperationException("Invalid selected Minecraft PID: " + target.Pid);
            }
            string commandLine = ReadProcessCommandLine(pid);
            string evidence = target.Label + Environment.NewLine + commandLine;
            string detected = MatchSupportedVersion(evidence);
            if (detected != null)
            {
                return detected;
            }

            throw new InvalidOperationException(
                "Unable to identify whether PID " + target.Pid
                + " is Minecraft 26.1.2, 26.2, or 26.3-pre-3. Load was cancelled to avoid loading "
                + "the wrong mappings.\r\n\r\n"
                + "Start the game normally so its command line contains --version, or run "
                + DisplayName + " with --minecraft-version 26.1/26.1.2/26.2/26.3-pre-3.");
        }

        private static string NormalizeConfiguredVersion(string configured)
        {
            string value = configured == null ? String.Empty : configured.Trim();
            if (String.Equals(value, "26.1", StringComparison.OrdinalIgnoreCase)
                || String.Equals(value, "26.1.2", StringComparison.OrdinalIgnoreCase))
            {
                return "26.1";
            }
            if (String.Equals(value, "26.3-pre-3", StringComparison.OrdinalIgnoreCase))
            {
                return "26.3";
            }
            return String.Equals(value, "26.2", StringComparison.OrdinalIgnoreCase)
                ? "26.2" : null;
        }

        private static string MatchSupportedVersion(string evidence)
        {
            if (String.IsNullOrWhiteSpace(evidence))
            {
                return null;
            }
            bool is26_1 = Regex.IsMatch(evidence,
                @"(?<![0-9.])26\.1\.2(?![0-9.])",
                RegexOptions.IgnoreCase | RegexOptions.CultureInvariant);
            bool is26_2 = Regex.IsMatch(evidence,
                @"(?<![0-9.])26\.2(?![0-9.])",
                RegexOptions.IgnoreCase | RegexOptions.CultureInvariant);
            bool is26_3 = Regex.IsMatch(evidence,
                @"(?<![0-9A-Za-z.\-])26\.3-pre-3(?![0-9A-Za-z.\-])",
                RegexOptions.IgnoreCase | RegexOptions.CultureInvariant);
            if ((is26_1 ? 1 : 0) + (is26_2 ? 1 : 0) + (is26_3 ? 1 : 0) != 1)
            {
                return null;
            }
            return is26_3 ? "26.3" : is26_2 ? "26.2" : "26.1";
        }

        private static string ReadProcessCommandLine(int pid)
        {
            return ProcessEvidenceCache.CommandLine(pid);
        }

        private static string OptionArgument(string[] arguments, string name)
        {
            for (int index = 0; index < arguments.Length; index++)
            {
                if (String.Equals(arguments[index], name, StringComparison.OrdinalIgnoreCase)
                    && index + 1 < arguments.Length)
                {
                    return arguments[index + 1];
                }
            }
            return null;
        }

        private static IList<MinecraftTarget> DiscoverTargets()
        {
            List<MinecraftTarget> result = new List<MinecraftTarget>();
            HashSet<string> seen = new HashSet<string>(StringComparer.Ordinal);
            AddWindowsMinecraftProcesses(result, seen);
            return result;
        }

        private static void AddWindowsMinecraftProcesses(
            IList<MinecraftTarget> targets,
            ISet<string> seen)
        {
            foreach (Process process in Process.GetProcesses())
            {
                using (process)
                {
                    try
                    {
                        string name = process.ProcessName.ToLowerInvariant();
                        if (name != "java" && name != "javaw")
                        {
                            continue;
                        }
                        string executable = process.MainModule == null
                            ? String.Empty : process.MainModule.FileName;
                        string title = FindProcessWindowTitle(
                            process.Id, process.MainWindowTitle);
                        string commandLine = ReadProcessCommandLine(process.Id);
                        if (!IsMinecraftTargetEvidence(executable, title, commandLine))
                        {
                            continue;
                        }
                        string pid = process.Id.ToString();
                        string display = title.Length > 0
                            ? title : FallbackMinecraftDisplay(executable);
                        string detectedVersion = MatchSupportedVersion(
                            display + Environment.NewLine + commandLine);
                        string label = pid + "  " + display
                            + (detectedVersion == null ? "  [version unknown]"
                                : "  [Minecraft " + detectedVersion + "]");
                        if (seen.Add(pid)) targets.Add(new MinecraftTarget(pid, label));
                    }
                    catch (Win32Exception)
                    {
                    }
                    catch (InvalidOperationException)
                    {
                    }
                }
            }
        }

        private static bool IsMinecraftTargetEvidence(
            string executable,
            string title,
            string commandLine)
        {
            string normalizedPath = (executable ?? String.Empty).ToLowerInvariant();
            string normalizedTitle = (title ?? String.Empty).ToLowerInvariant();
            string normalizedCommand = (commandLine ?? String.Empty).ToLowerInvariant();
            bool officialRuntime = normalizedPath.Contains(".minecraft")
                && normalizedPath.Contains("runtime");
            bool knownClientRuntime = normalizedPath.Contains(".lunarclient")
                || normalizedPath.Contains("feather")
                || normalizedPath.Contains("multimc")
                || normalizedPath.Contains("prismlauncher");
            bool knownWindow = normalizedTitle.Contains("minecraft")
                || normalizedTitle.Contains("lunar client");
            bool minecraftMain = normalizedCommand.Contains("net.minecraft.client.main.main")
                || normalizedCommand.Contains("knotclient")
                || normalizedCommand.Contains("launchwrapper")
                || normalizedCommand.Contains("bootstraplauncher");
            return officialRuntime || knownClientRuntime || knownWindow || minecraftMain;
        }

        private static string FallbackMinecraftDisplay(string executable)
        {
            string normalizedPath = (executable ?? String.Empty).ToLowerInvariant();
            if (normalizedPath.Contains(".lunarclient")) return "Lunar Client";
            if (normalizedPath.Contains("feather")) return "Feather Client";
            if (normalizedPath.Contains("prismlauncher")) return "Prism Launcher Minecraft";
            if (normalizedPath.Contains("multimc")) return "MultiMC Minecraft";
            return String.IsNullOrWhiteSpace(executable) ? "Minecraft Java" : executable;
        }

        private static string FindProcessWindowTitle(int processId, string primaryTitle)
        {
            if (!String.IsNullOrWhiteSpace(primaryTitle)) return primaryTitle.Trim();
            string best = String.Empty;
            try
            {
                EnumWindows(delegate(IntPtr window, IntPtr parameter)
                {
                    if (!IsWindowVisible(window)) return true;
                    uint owner;
                    GetWindowThreadProcessId(window, out owner);
                    if (owner != (uint)processId) return true;
                    int length = GetWindowTextLength(window);
                    if (length <= 0 || length > 4096) return true;
                    StringBuilder text = new StringBuilder(length + 1);
                    if (GetWindowText(window, text, text.Capacity) <= 0) return true;
                    string candidate = text.ToString().Trim();
                    if (candidate.Length > best.Length) best = candidate;
                    return true;
                }, IntPtr.Zero);
            }
            catch (DllNotFoundException)
            {
            }
            catch (EntryPointNotFoundException)
            {
            }
            return best;
        }

        private static string ResolveHome()
        {
            string configured = Environment.GetEnvironmentVariable("MOONS_HOME");
            if (!String.IsNullOrWhiteSpace(configured))
            {
                return Path.GetFullPath(configured.Trim());
            }
            return Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),
                ".moons");
        }

        private static string ResolveDisplayName()
        {
            string environment = NormalizeDisplayName(
                Environment.GetEnvironmentVariable("MOONS_NAME"));
            if (environment != null)
            {
                return environment;
            }

            try
            {
                string config = Path.Combine(ResolveHome(), "config", "moons.properties");
                if (File.Exists(config))
                {
                    foreach (string line in File.ReadAllLines(config))
                    {
                        Match match = Regex.Match(line,
                            @"^\s*client\.name\s*[:=]\s*(.*)$",
                            RegexOptions.CultureInvariant);
                        if (!match.Success)
                        {
                            continue;
                        }
                        string value = Regex.Replace(match.Groups[1].Value,
                            @"\\u([0-9a-fA-F]{4})",
                            unicode => ((char)Convert.ToInt32(
                                unicode.Groups[1].Value, 16)).ToString());
                        value = value.Replace(@"\ ", " ")
                            .Replace(@"\:", ":")
                            .Replace(@"\=", "=")
                            .Replace(@"\\", @"\");
                        string configured = NormalizeDisplayName(value);
                        if (configured != null)
                        {
                            return configured;
                        }
                    }
                }
            }
            catch
            {
                // Branding must never prevent the loader from starting.
            }
            return DefaultDisplayName;
        }

        private static string NormalizeDisplayName(string value)
        {
            if (String.IsNullOrWhiteSpace(value))
            {
                return null;
            }
            string normalized = Regex.Replace(value, @"[\x00-\x1F\x7F]", "").Trim();
            if (normalized.Length == 0)
            {
                return null;
            }
            return normalized.Length <= 32 ? normalized : normalized.Substring(0, 32);
        }

        private static string EnsureUiRuntime(
            string home,
            string[] arguments,
            Action<int, string> progress,
            Action<int, string> downloadProgress,
            Func<bool> cancelled)
        {
            Dictionary<string, string> metadata = RuntimeMetadata.Parse(
                Encoding.UTF8.GetString(ReadResourceBytes(UiRuntimeMetadataResource)));
            string expectedHash;
            if (!metadata.TryGetValue("sha256", out expectedHash)
                || !Regex.IsMatch(expectedHash, "^[0-9a-fA-F]{64}$"))
            {
                throw new InvalidDataException("The embedded UI runtime hash is invalid.");
            }
            expectedHash = expectedHash.ToLowerInvariant();

            long expectedSize = 0L;
            string configuredSize;
            if (metadata.TryGetValue("size", out configuredSize))
            {
                Int64.TryParse(configuredSize, out expectedSize);
            }

            string libraryRoot = Path.Combine(home, "libraries");
            string versionDirectory = Path.Combine(libraryRoot, expectedHash);
            string target = Path.Combine(versionDirectory, "moons-ui-runtime.jar");
            Directory.CreateDirectory(versionDirectory);
            if (!IsExpectedFile(target, expectedHash, expectedSize))
            {
                if (progress != null) progress(6, "Preparing UI runtime dependencies");
                StagedFile.Write(target, temporary =>
                {
                    if (!ExtractBundledUiRuntime(temporary, cancelled))
                    {
                        string url = ResolveUiRuntimeUrl(home, arguments, metadata);
                        if (String.IsNullOrWhiteSpace(url))
                        {
                            throw new InvalidOperationException(
                                "The UI runtime is not cached and no download URL is configured.\r\n\r\n"
                                + "Set --ui-dependency-url, MOONS_UI_DOWNLOAD_URL, "
                                + "ui.dependency-url in .moons\\config\\moons.properties, or build with "
                                + "-Pmoons_ui_download_url=https://.../moons-ui-runtime.jar.");
                        }
                        if (progress != null) progress(6, "Downloading UI runtime dependencies");
                        DownloadFile(CreateDownloadUri(url), temporary, expectedSize,
                            progress, downloadProgress, cancelled);
                    }
                    if (!IsExpectedFile(temporary, expectedHash, expectedSize))
                    {
                        throw new InvalidDataException(
                            "The UI runtime failed its SHA-256 or size check.");
                    }
                });
                if (downloadProgress != null)
                {
                    downloadProgress(100, "UI runtime dependencies ready");
                }
            }

            WriteUiRuntimePointer(libraryRoot, expectedHash);
            if (progress != null) progress(17, "UI runtime dependencies are ready");
            return target;
        }

        private static bool ExtractBundledUiRuntime(string target, Func<bool> cancelled)
        {
            using (Stream source = Assembly.GetExecutingAssembly().GetManifestResourceStream(UiRuntimeResource))
            {
                if (source == null) return false;
                if (cancelled()) throw new OperationCanceledException();
                using (FileStream output = new FileStream(target, FileMode.Create, FileAccess.Write, FileShare.None))
                {
                    source.CopyTo(output);
                }
                if (cancelled()) throw new OperationCanceledException();
                return true;
            }
        }

        private static string ResolveUiRuntimeUrl(
            string home,
            string[] arguments,
            IDictionary<string, string> metadata)
        {
            string value = OptionArgument(arguments, "--ui-dependency-url");
            if (!String.IsNullOrWhiteSpace(value)) return value.Trim();

            value = Environment.GetEnvironmentVariable("MOONS_UI_DOWNLOAD_URL");
            if (!String.IsNullOrWhiteSpace(value)) return value.Trim();

            string config = Path.Combine(home, "config", "moons.properties");
            if (File.Exists(config))
            {
                Dictionary<string, string> properties = RuntimeMetadata.Parse(
                    File.ReadAllText(config, Encoding.UTF8));
                if (properties.TryGetValue("ui.dependency-url", out value)
                    && !String.IsNullOrWhiteSpace(value))
                {
                    return value.Trim();
                }
            }

            return metadata.TryGetValue("url", out value) ? value.Trim() : String.Empty;
        }

        private static Uri CreateDownloadUri(string value)
        {
            Uri uri;
            if (!Uri.TryCreate(value, UriKind.Absolute, out uri))
            {
                uri = new Uri(Path.GetFullPath(value));
            }
            if (!String.Equals(uri.Scheme, Uri.UriSchemeHttps, StringComparison.OrdinalIgnoreCase)
                && !String.Equals(uri.Scheme, Uri.UriSchemeHttp, StringComparison.OrdinalIgnoreCase)
                && !String.Equals(uri.Scheme, Uri.UriSchemeFile, StringComparison.OrdinalIgnoreCase))
            {
                throw new InvalidOperationException(
                    "Unsupported UI dependency URL scheme: " + uri.Scheme);
            }
            return uri;
        }

        private static void DownloadFile(
            Uri source,
            string target,
            long expectedSize,
            Action<int, string> progress,
            Action<int, string> downloadProgress,
            Func<bool> cancelled)
        {
            ServicePointManager.SecurityProtocol |= SecurityProtocolType.Tls12;
            using (WebClient client = new WebClient())
            {
                client.Headers[HttpRequestHeader.UserAgent] = "Moons-Launcher/1.0";
                using (Stream input = client.OpenRead(source))
                using (FileStream output = new FileStream(
                    target, FileMode.Create, FileAccess.Write, FileShare.None))
                {
                    long total = expectedSize;
                    long headerSize;
                    if (client.ResponseHeaders != null
                        && Int64.TryParse(client.ResponseHeaders["Content-Length"],
                            out headerSize)
                        && headerSize > 0L)
                    {
                        total = headerSize;
                    }

                    byte[] buffer = new byte[128 * 1024];
                    long written = 0L;
                    if (downloadProgress != null)
                    {
                        downloadProgress(0, "0.0 MB / " + FormatMegabytes(total));
                    }
                    int count;
                    while ((count = input.Read(buffer, 0, buffer.Length)) > 0)
                    {
                        ThrowIfCancelled(cancelled);
                        output.Write(buffer, 0, count);
                        written += count;
                        if (progress != null && total > 0L)
                        {
                            int percent = 6 + (int)Math.Min(10L, written * 10L / total);
                            progress(percent, "Downloading UI runtime dependencies");
                        }
                        if (downloadProgress != null && total > 0L)
                        {
                            int percent = (int)Math.Min(100L, written * 100L / total);
                            downloadProgress(percent, percent + "%   "
                                + FormatMegabytes(written) + " / " + FormatMegabytes(total));
                        }
                    }
                }
            }
        }

        private static string FormatMegabytes(long bytes)
        {
            return (bytes / 1048576.0).ToString("0.0") + " MB";
        }

        private static bool IsExpectedFile(string path, string hash, long size)
        {
            if (!File.Exists(path)) return false;
            FileInfo file = new FileInfo(path);
            return (size <= 0L || file.Length == size)
                && String.Equals(Hashing.Sha256(path), hash, StringComparison.OrdinalIgnoreCase);
        }

        private static void WriteUiRuntimePointer(string libraryRoot, string hash)
        {
            Directory.CreateDirectory(libraryRoot);
            string pointer = Path.Combine(libraryRoot, "moons-ui-runtime.current");
            StagedFile.Write(pointer, temporary => File.WriteAllText(temporary,
                hash + "/moons-ui-runtime.jar\n", new UTF8Encoding(false)));
        }

        private static string ExtractPayload(string home, string version)
        {
            if (String.Equals(version, "26.1", StringComparison.Ordinal))
            {
                return ExtractResource(home, Payload26_1Resource, "moons-26.1.jar");
            }
            if (String.Equals(version, "26.2", StringComparison.Ordinal))
            {
                return ExtractPatchedPayload(home, Payload26_2PatchResource, "moons-26.2.jar");
            }
            if (String.Equals(version, "26.3", StringComparison.Ordinal))
            {
                return ExtractPatchedPayload(home, Payload26_3PatchResource, "moons-26.3.jar");
            }
            throw new InvalidOperationException("No embedded payload for Minecraft " + version + ".");
        }

        private static string ExtractPatchedPayload(string home, string patchResource, string fileName)
        {
            byte[] basePayload = ReadResourceBytes(Payload26_1Resource);
            byte[] patch = ReadResourceBytes(patchResource);
            string identity = Hashing.Sha256(Encoding.UTF8.GetBytes(
                Hashing.Sha256(basePayload) + ":" + Hashing.Sha256(patch)));
            string directory = Path.Combine(home, "cache", "launcher", identity);
            string target = Path.Combine(directory, fileName);
            Directory.CreateDirectory(directory);
            if (File.Exists(target))
            {
                return target;
            }

            StagedFile.Write(target, temporary => RebuildPatchedPayload(basePayload, patch, temporary));
            return target;
        }

        private static void RebuildPatchedPayload(
            byte[] basePayload,
            byte[] patchBytes,
            string target)
        {
            using (MemoryStream baseStream = new MemoryStream(basePayload, false))
            using (MemoryStream patchStream = new MemoryStream(patchBytes, false))
            using (ZipArchive baseArchive = new ZipArchive(
                baseStream, ZipArchiveMode.Read, false))
            using (ZipArchive patchArchive = new ZipArchive(
                patchStream, ZipArchiveMode.Read, false))
            using (FileStream targetStream = new FileStream(
                target, FileMode.Create, FileAccess.Write, FileShare.None))
            using (ZipArchive targetArchive = new ZipArchive(
                targetStream, ZipArchiveMode.Create, false))
            {
                HashSet<string> outerDeletes = ReadDeletionList(
                    patchArchive, "META-INF/moons-patch/outer-deletions.txt");
                HashSet<string> featureDeletes = ReadDeletionList(
                    patchArchive, "META-INF/moons-patch/feature-deletions.txt");
                Dictionary<string, ZipArchiveEntry> outerChanges = ReadChanges(
                    patchArchive, "outer/");
                Dictionary<string, ZipArchiveEntry> featureChanges = ReadChanges(
                    patchArchive, "features/");

                ZipArchiveEntry baseFeatures = baseArchive.GetEntry(FeaturesJarEntry);
                if (baseFeatures == null)
                {
                    throw new InvalidDataException(
                        "The embedded base payload has no feature module.");
                }
                byte[] rebuiltFeatures = RebuildFeatures(
                    baseFeatures, featureDeletes, featureChanges);

                foreach (ZipArchiveEntry entry in baseArchive.Entries)
                {
                    if (ZipEntries.IsDirectory(entry)
                        || outerDeletes.Contains(entry.FullName)
                        || outerChanges.ContainsKey(entry.FullName))
                    {
                        continue;
                    }
                    if (String.Equals(entry.FullName, FeaturesJarEntry,
                        StringComparison.Ordinal))
                    {
                        ZipEntries.Write(targetArchive, entry.FullName, rebuiltFeatures);
                    }
                    else
                    {
                        ZipEntries.Copy(entry, targetArchive, entry.FullName);
                    }
                }
                foreach (KeyValuePair<string, ZipArchiveEntry> change in outerChanges)
                {
                    ZipEntries.Copy(change.Value, targetArchive, change.Key);
                }
            }
        }

        private static byte[] RebuildFeatures(
            ZipArchiveEntry baseFeatures,
            HashSet<string> deletes,
            Dictionary<string, ZipArchiveEntry> changes)
        {
            byte[] baseBytes = ZipEntries.ReadBytes(baseFeatures);
            using (MemoryStream input = new MemoryStream(baseBytes, false))
            using (ZipArchive baseArchive = new ZipArchive(input, ZipArchiveMode.Read, false))
            using (MemoryStream output = new MemoryStream())
            {
                using (ZipArchive targetArchive = new ZipArchive(
                    output, ZipArchiveMode.Create, true))
                {
                    foreach (ZipArchiveEntry entry in baseArchive.Entries)
                    {
                        if (ZipEntries.IsDirectory(entry)
                            || deletes.Contains(entry.FullName)
                            || changes.ContainsKey(entry.FullName))
                        {
                            continue;
                        }
                        ZipEntries.Copy(entry, targetArchive, entry.FullName);
                    }
                    foreach (KeyValuePair<string, ZipArchiveEntry> change in changes)
                    {
                        ZipEntries.Copy(change.Value, targetArchive, change.Key);
                    }
                }
                return output.ToArray();
            }
        }

        private static Dictionary<string, ZipArchiveEntry> ReadChanges(
            ZipArchive archive,
            string prefix)
        {
            Dictionary<string, ZipArchiveEntry> result =
                new Dictionary<string, ZipArchiveEntry>(StringComparer.Ordinal);
            foreach (ZipArchiveEntry entry in archive.Entries)
            {
                if (!ZipEntries.IsDirectory(entry)
                    && entry.FullName.StartsWith(prefix, StringComparison.Ordinal))
                {
                    result.Add(entry.FullName.Substring(prefix.Length), entry);
                }
            }
            return result;
        }

        private static HashSet<string> ReadDeletionList(
            ZipArchive archive,
            string name)
        {
            HashSet<string> result = new HashSet<string>(StringComparer.Ordinal);
            ZipArchiveEntry entry = archive.GetEntry(name);
            if (entry == null)
            {
                throw new InvalidDataException("Payload patch metadata is missing: " + name);
            }
            string text = Encoding.UTF8.GetString(ZipEntries.ReadBytes(entry));
            foreach (string line in text.Split(new[] { '\r', '\n' },
                StringSplitOptions.RemoveEmptyEntries))
            {
                result.Add(line);
            }
            return result;
        }

        private static string ExtractBootstrapApi(string home)
        {
            return ExtractResource(home, BootstrapApiResource, "moons-api.jar");
        }

        private static string ExtractBridge(string home)
        {
            return ExtractResource(home, BridgeResource, "moons-bridge.dll");
        }

        private static string ExtractResource(
            string home,
            string resourceName,
            string fileName)
        {
            byte[] bytes = ReadResourceBytes(resourceName);

            string digest = Hashing.Sha256(bytes);
            string directory = Path.Combine(home, "cache", "launcher", digest);
            string target = Path.Combine(directory, fileName);
            Directory.CreateDirectory(directory);
            if (File.Exists(target) && Hashing.Sha256(target) == digest)
            {
                return target;
            }

            StagedFile.Write(target, temporary => File.WriteAllBytes(temporary, bytes));
            return target;
        }

        private static byte[] ReadResourceBytes(string resourceName)
        {
            Assembly assembly = Assembly.GetExecutingAssembly();
            using (Stream resource = assembly.GetManifestResourceStream(resourceName))
            {
                if (resource == null)
                {
                    throw new InvalidOperationException(
                        "The embedded resource is missing: " + resourceName);
                }
                using (MemoryStream memory = new MemoryStream())
                {
                    resource.CopyTo(memory);
                    return memory.ToArray();
                }
            }
        }

        private static void LoadBridge(
            string bridge,
            string payload,
            string bootstrapApi,
            string home,
            string hardwareId,
            string pidText,
            Action<int, string> progress,
            Func<bool> cancelled)
        {
            int pid;
            if (!Int32.TryParse(pidText, out pid) || pid <= 0)
            {
                throw new ArgumentException("Invalid target PID: " + pidText);
            }
            if (pid == Process.GetCurrentProcess().Id)
            {
                throw new InvalidOperationException("Refusing to load the launcher itself.");
            }
            if (!Environment.Is64BitProcess)
            {
                throw new InvalidOperationException("The JVMTI launcher must run as a 64-bit process.");
            }

            using (LoadLease lease = LoadLease.Acquire(pid, cancelled))
            {
                LoadBridgeLocked(bridge, payload, bootstrapApi, home, hardwareId,
                    pid, progress, cancelled);
            }
        }

        private static void LoadBridgeLocked(
            string bridge,
            string payload,
            string bootstrapApi,
            string home,
            string hardwareId,
            int pid,
            Action<int, string> progress,
            Func<bool> cancelled)
        {
            progress(52, "Validating target identity and architecture");
            ThrowIfCancelled(cancelled);
            DateTime targetStarted;
            string initialImage;
            using (Process target = Process.GetProcessById(pid))
            {
                string name = target.ProcessName ?? String.Empty;
                if (!String.Equals(name, "java", StringComparison.OrdinalIgnoreCase)
                    && !String.Equals(name, "javaw", StringComparison.OrdinalIgnoreCase))
                {
                    throw new InvalidOperationException(
                        "PID " + pid + " is " + name + ", not java/javaw.");
                }
                targetStarted = target.StartTime.ToUniversalTime();
                initialImage = target.MainModule == null
                    ? String.Empty : target.MainModule.FileName;
                try
                {
                    foreach (ProcessModule module in target.Modules)
                    {
                        if (String.Equals(module.ModuleName, "moons-bridge.dll",
                            StringComparison.OrdinalIgnoreCase))
                        {
                            progress(100, DisplayName + " JVMTI bridge is already active in this process");
                            return;
                        }
                    }
                }
                catch (Win32Exception)
                {
                    // Handle-based validation below remains authoritative.
                }
            }

            const uint processAccess = 0x0002 | 0x0400 | 0x0008 | 0x0020
                | 0x0010 | 0x00100000;
            IntPtr processHandle = OpenProcess(processAccess, false, pid);
            if (processHandle == IntPtr.Zero) ThrowWin32("OpenProcess");
            IntPtr remotePath = IntPtr.Zero;
            IntPtr remoteThread = IntPtr.Zero;
            bool remoteThreadCompleted = false;
            string configPath = null;
            try
            {
                if (GetProcessId(processHandle) != (uint)pid)
                {
                    throw new InvalidOperationException("Target identity changed while opening the PID.");
                }
                StringBuilder image = new StringBuilder(32768);
                int imageSize = image.Capacity;
                if (!QueryFullProcessImageName(processHandle, 0, image, ref imageSize))
                {
                    ThrowWin32("QueryFullProcessImageName");
                }
                string openedImage = image.ToString();
                string openedName = Path.GetFileNameWithoutExtension(openedImage);
                if (!String.Equals(openedName, "java", StringComparison.OrdinalIgnoreCase)
                    && !String.Equals(openedName, "javaw", StringComparison.OrdinalIgnoreCase))
                {
                    throw new InvalidOperationException(
                        "Opened PID resolved to a non-Java image: " + openedImage);
                }
                using (Process target = Process.GetProcessById(pid))
                {
                    if (target.HasExited
                        || target.StartTime.ToUniversalTime() != targetStarted)
                    {
                        throw new InvalidOperationException("Target exited or PID was reused.");
                    }
                }

                ushort processMachine;
                ushort nativeMachine;
                if (!IsWow64Process2(processHandle, out processMachine, out nativeMachine))
                {
                    ThrowWin32("IsWow64Process2");
                }
                ushort targetMachine = processMachine == 0 ? nativeMachine : processMachine;
                ushort bridgeMachine = ReadPeMachine(bridge);
                if (targetMachine != bridgeMachine || bridgeMachine != 0x8664)
                {
                    throw new InvalidOperationException(String.Format(
                        "Architecture mismatch: target=0x{0:X4}, bridge=0x{1:X4}.",
                        targetMachine, bridgeMachine));
                }
                if (!String.IsNullOrWhiteSpace(initialImage)
                    && !String.Equals(Path.GetFullPath(initialImage), Path.GetFullPath(openedImage),
                        StringComparison.OrdinalIgnoreCase))
                {
                    throw new InvalidOperationException("Target image changed during validation.");
                }

                progress(60, "Preparing one-shot JVM bridge configuration");
                ThrowIfCancelled(cancelled);
                string attempt = Guid.NewGuid().ToString("N");
                string dataDirectory = Path.Combine(
                    Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),
                    ".moons");
                Directory.CreateDirectory(dataDirectory);
                Directory.CreateDirectory(home);
                configPath = Path.Combine(dataDirectory, "bridge-" + pid + ".conf");
                File.WriteAllText(configPath,
                    "payload=" + payload + Environment.NewLine
                    + "bootstrap=" + bootstrapApi + Environment.NewLine
                    + "home=" + home + Environment.NewLine
                    + "name=" + DisplayName + Environment.NewLine
                    + "hwid=" + hardwareId + Environment.NewLine
                    + "attempt=" + attempt,
                    new UTF8Encoding(false));
                long bridgeLogOffset = BridgeAttemptMonitor.CaptureOffset(dataDirectory);

                progress(70, "Loading JVMTI bridge into target JVM");
                byte[] pathBytes = Encoding.Unicode.GetBytes(bridge + "\0");
                remotePath = VirtualAllocEx(processHandle, IntPtr.Zero,
                    new UIntPtr((uint)pathBytes.Length), 0x3000, 0x04);
                if (remotePath == IntPtr.Zero) ThrowWin32("VirtualAllocEx");
                UIntPtr written;
                if (!WriteProcessMemory(processHandle, remotePath, pathBytes,
                    new UIntPtr((uint)pathBytes.Length), out written))
                {
                    ThrowWin32("WriteProcessMemory");
                }
                if (written.ToUInt64() != (ulong)pathBytes.Length)
                {
                    throw new InvalidOperationException("WriteProcessMemory wrote a partial DLL path.");
                }
                IntPtr kernel32 = GetModuleHandle("kernel32.dll");
                if (kernel32 == IntPtr.Zero) ThrowWin32("GetModuleHandle(kernel32.dll)");
                IntPtr loadLibrary = GetProcAddress(kernel32, "LoadLibraryW");
                if (loadLibrary == IntPtr.Zero) ThrowWin32("GetProcAddress(LoadLibraryW)");
                uint threadId;
                remoteThread = CreateRemoteThread(processHandle, IntPtr.Zero, UIntPtr.Zero,
                    loadLibrary, remotePath, 0, out threadId);
                if (remoteThread == IntPtr.Zero) ThrowWin32("CreateRemoteThread");

                DateTime loadDeadline = DateTime.UtcNow.AddSeconds(15);
                while (true)
                {
                    ThrowIfCancelled(cancelled);
                    uint processWait = WaitForSingleObject(processHandle, 0);
                    if (processWait == 0)
                    {
                        throw new InvalidOperationException(
                            "The target JVM exited while loading the JVMTI bridge.");
                    }
                    if (processWait == 0xFFFFFFFF)
                    {
                        ThrowWin32("WaitForSingleObject(target process)");
                    }
                    uint wait = WaitForSingleObject(remoteThread, 200);
                    if (wait == 0)
                    {
                        remoteThreadCompleted = true;
                        break;
                    }
                    if (wait != 0x102) ThrowWin32("WaitForSingleObject");
                    if (DateTime.UtcNow >= loadDeadline)
                    {
                        throw new TimeoutException(
                            "LoadLibraryW did not finish within 15 seconds.");
                    }
                }
                uint loadResult;
                if (!GetExitCodeThread(remoteThread, out loadResult))
                {
                    ThrowWin32("GetExitCodeThread");
                }
                if (loadResult == 0)
                {
                    throw new InvalidOperationException(
                        "LoadLibraryW returned NULL; the bridge DLL was not loaded.");
                }

                progress(86, "Waiting for Runtime and initial retransformation");
                BridgeAttemptMonitor.WaitForReady(
                    dataDirectory, attempt, processHandle, bridgeLogOffset, cancelled);
                progress(100, "Runtime and JVMTI hooks are ready for the selected process");
            }
            finally
            {
                if (remoteThread != IntPtr.Zero) CloseHandle(remoteThread);
                if (remotePath != IntPtr.Zero && remoteThreadCompleted)
                {
                    VirtualFreeEx(processHandle, remotePath, UIntPtr.Zero, 0x8000);
                }
                CloseHandle(processHandle);
            }
        }

        private static ushort ReadPeMachine(string path)
        {
            using (FileStream stream = File.OpenRead(path))
            using (BinaryReader reader = new BinaryReader(stream))
            {
                if (reader.ReadUInt16() != 0x5A4D)
                {
                    throw new InvalidDataException("Not a PE image: " + path);
                }
                stream.Position = 0x3C;
                int peOffset = reader.ReadInt32();
                if (peOffset < 0 || peOffset > stream.Length - 6)
                {
                    throw new InvalidDataException("Invalid PE header: " + path);
                }
                stream.Position = peOffset;
                if (reader.ReadUInt32() != 0x00004550)
                {
                    throw new InvalidDataException("Invalid PE signature: " + path);
                }
                return reader.ReadUInt16();
            }
        }

        private static void ThrowWin32(string operation)
        {
            int error = Marshal.GetLastWin32Error();
            throw new Win32Exception(error, operation + " failed");
        }

        private static bool Contains(string[] values, string expected)
        {
            foreach (string value in values)
            {
                if (String.Equals(value, expected, StringComparison.OrdinalIgnoreCase))
                {
                    return true;
                }
            }
            return false;
        }

    }
}
