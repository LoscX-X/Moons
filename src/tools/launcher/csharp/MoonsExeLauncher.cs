using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.IO.Compression;
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

namespace Moons.WindowsLauncher
{
    internal static class Program
    {
        private const string DefaultDisplayName = "Moons";
        private static readonly string DisplayName = ResolveDisplayName();
        private const string Payload189Resource = "Moons.Payload.1_8.jar";
        private const string BootstrapApiResource = "Moons.Api.jar";
        private const string BridgeResource = "Moons.Bridge.dll";

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
            if (Array.IndexOf(arguments, "--version") >= 0)
            {
                Console.WriteLine(DependencyRuntime.VersionDetails());
                return 0;
            }
            if (Contains(arguments, "--self-test-version-detection"))
            {
                return SelfTestVersionDetection();
            }
            if (Contains(arguments, "--verify-dependencies"))
            {
                try { DependencyRuntime.Verify(ResolveHome()); return 0; }
                catch (Exception error) { Console.Error.WriteLine(error.Message); return 1; }
            }
            if (Contains(arguments, "--install-ysm-only"))
            {
                Console.Error.WriteLine("Run Moons-install.exe to install or update dependencies.");
                return 1;
            }
            if (Contains(arguments, "--extract-only"))
            {
                try
                {
                    string home = ResolveHome();
                    DependencyRuntime.Verify(home);
                    ExtractPayload(home, "1.8.9");
                    ExtractBootstrapApi(home);
                    ExtractBridge(home);
                    HardwareIdGenerator.Generate();
                    return 0;
                }
                catch (Exception error)
                {
                    LauncherTheme.ShowError(null, "Unable to load.", error.Message);
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
            string targetSnapshot = OptionArgument(arguments, "--self-test-target-ui-snapshot");
            if (!String.IsNullOrWhiteSpace(targetSnapshot))
                return LauncherTheme.SaveSnapshot(new TargetDialog(new List<MinecraftTarget> {
                    new MinecraftTarget("12480", "Minecraft 1.8.9 · PID 12480"),
                    new MinecraftTarget("17320", "Minecraft 1.8.9 · PID 17320")
                }), targetSnapshot);
            string errorSnapshot = OptionArgument(arguments, "--self-test-error-ui-snapshot");
            if (!String.IsNullOrWhiteSpace(errorSnapshot))
                return LauncherTheme.SaveSnapshot(new ThemeMessageDialog("Unable to load.",
                    "The selected Minecraft client is no longer running.\r\nStart the game, then try again."), errorSnapshot);
            LoaderForm form = new LoaderForm(arguments);
            Application.Run(form);
            return form.ExitCode;
        }

        private static int SaveLoaderSnapshot(string outputPath)
        {
            try
            {
                LoaderForm form = new LoaderForm(new string[0], false);
                return LauncherTheme.SaveSnapshot(form, outputPath);
            }
            catch (Exception error) { Console.Error.WriteLine(error); return 4; }
        }

        private static int SelfTestVersionDetection()
        {
            bool passed = NormalizeConfiguredVersion("1.8.9") == "1.8.9";
            foreach (string evidence in new[] { "net.minecraft.client.main.Main --version 1.8.9",
                @"C:\Games\Minecraft\versions\1.8.9\1.8.9.jar", "Minecraft 1.8.9", "1.8.9.jar" })
                passed &= MatchSupportedVersion(evidence) == "1.8.9";
            foreach (string unsupported in new[] { "1.8", "1.8.8", "1.8.90", "11.8.9", "1.8.9-custom",
                "1.8.9.1", "1.8.9_pre", "26.1.2", "26.2", "26.3", "26.4-snapshot-1", "1.21.5" })
            {
                passed &= NormalizeConfiguredVersion(unsupported) == null;
                passed &= MatchSupportedVersion(unsupported) == null;
            }
            passed &= IsMinecraftTargetEvidence(@"C:\Program Files\Java\jdk-25\bin\javaw.exe", "",
                "net.minecraft.client.main.Main --version 1.8.9");
            passed &= !IsMinecraftTargetEvidence(@"C:\Program Files\Java\jdk-25\bin\java.exe", "", "");
            passed &= MatchSupportedVersion("--version 1.12.2 -cp C:\\cache\\1.8.9\\client.jar") == null;
            passed &= MatchSupportedVersion("--version \"1.8.9\"") == "1.8.9";
            return passed ? 0 : 3;
        }

        private static void Execute(
            string[] arguments,
            Action<int, string> progress,
            Func<IList<MinecraftTarget>, MinecraftTarget> chooseTarget,
            Func<bool> cancelled)
        {
            string home = ResolveHome();
            CacheMaintenance.Run(home);
            progress(4, "Checking UI runtime dependencies");
            DependencyRuntime.Verify(home);
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
            ThrowIfCancelled(cancelled);
            progress(43, "Minecraft " + detectedVersion + " selected: " + target.Label);
            string payload = ExtractPayload(home, detectedVersion);
            progress(48, "Loaded embedded Minecraft " + detectedVersion + " payload");
            LoadBridge(bridge, payload, bootstrapApi, home, hardwareId,
                target.Pid, progress, cancelled);
        }

        private sealed class LoaderForm : ThemeForm
        {
            private readonly string[] arguments;
            private readonly BackgroundWorker worker;
            private readonly Label status;
            private readonly ThemeProgressBar progressTrack;
            private readonly Label percentage;
            private readonly System.Windows.Forms.Timer progressTimer;
            private readonly Stopwatch progressClock;
            private double displayedProgress;
            private double lastAnimationSeconds;
            private int targetProgress;
            private bool allowClose;
            private bool highResolutionTimer;

            internal int ExitCode { get; private set; }

            internal LoaderForm(string[] arguments) : this(arguments, true)
            {
            }

            internal LoaderForm(string[] arguments, bool autoStart) : base(DisplayName)
            {
                this.arguments = arguments;
                Text = DisplayName + " " + DependencyRuntime.VersionLabel();
                ClientSize = new Size(640, 390);
                InstallChrome(true, "CLIENT / LOADER");
                Label eyebrow = LauncherTheme.Label(this, "MOONS / MINECRAFT JAVA",
                    new Rectangle(32, 104, 576, 20), 8F, false, LauncherTheme.Muted);
                Label title = LauncherTheme.Label(this, "Load your client.",
                    new Rectangle(30, 130, 578, 48), 28F, true, LauncherTheme.Text);
                LauncherTheme.Label(this, "Start Minecraft. Moons will find your client.",
                    new Rectangle(32, 185, 576, 26), 10F, false, LauncherTheme.Muted);
                EnableDrag(eyebrow);
                EnableDrag(title);

                GlassPanel card = new GlassPanel();
                card.SetBounds(32, 230, 576, 94);
                Controls.Add(card);
                status = LauncherTheme.Label(card, "Preparing...",
                    new Rectangle(24, 21, 462, 26), 9F, false, LauncherTheme.Text);
                status.AutoEllipsis = true;
                percentage = LauncherTheme.Label(card, "0%",
                    new Rectangle(490, 21, 62, 26), 9F, false, LauncherTheme.Muted);
                percentage.TextAlign = ContentAlignment.TopRight;
                progressTrack = new ThemeProgressBar();
                progressTrack.SetBounds(24, 63, 528, 6);
                card.Controls.Add(progressTrack);
                LauncherTheme.Label(this, DependencyRuntime.VersionLabel().Split('|')[0].Trim(),
                    new Rectangle(32, 349, 350, 20), 8F, false, LauncherTheme.Soft);
                Label footer = LauncherTheme.Label(this, "FREE & OPEN SOURCE",
                    new Rectangle(403, 349, 205, 20), 7.5F, false, LauncherTheme.Soft);
                footer.TextAlign = ContentAlignment.TopRight;

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
                if (!autoStart)
                {
                    progressTrack.Value = 42;
                    percentage.Text = "42%";
                    status.Text = "Waiting for a Minecraft client";
                }
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
                int value = Math.Max(0, Math.Min(100, eventArgs.ProgressPercentage));
                targetProgress = Math.Max(targetProgress, value);
                status.Text = eventArgs.UserState == null
                    ? "Working..." : eventArgs.UserState.ToString();
            }

            private void AnimateProgress(object sender, EventArgs eventArgs)
            {
                double now = progressClock.Elapsed.TotalSeconds;
                double deltaSeconds = now - lastAnimationSeconds;
                lastAnimationSeconds = now;
                AnimationSeconds = now;
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
            }

            private void RenderProgress()
            {
                progressTrack.Value = displayedProgress;
                percentage.Text = ((int)Math.Round(displayedProgress)).ToString() + "%";
            }

            private void Completed(object sender, RunWorkerCompletedEventArgs eventArgs)
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
                    LauncherTheme.ShowError(this, "Unable to load.", eventArgs.Error.Message);
                    Close();
                    return;
                }

                ExitCode = 0;
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
            }

        }

        private sealed class TargetDialog : ThemeForm
        {
            private readonly ListBox targets;

            internal MinecraftTarget SelectedTarget
            {
                get { return targets.SelectedItem as MinecraftTarget; }
            }

            internal TargetDialog(IList<MinecraftTarget> candidates) : base(DisplayName)
            {
                Text = "Select Minecraft — " + DisplayName;
                ClientSize = new Size(640, 420);
                ShowInTaskbar = false;
                StartPosition = FormStartPosition.CenterParent;
                InstallChrome(false, "CLIENT / SELECT");
                Label title = LauncherTheme.Label(this, "Choose Minecraft.",
                    new Rectangle(30, 103, 578, 46), 26F, true, LauncherTheme.Text);
                LauncherTheme.Label(this, "More than one Minecraft client is open.",
                    new Rectangle(32, 155, 576, 26), 10F, false, LauncherTheme.Muted);
                EnableDrag(title);
                GlassPanel listCard = new GlassPanel();
                listCard.SetBounds(32, 200, 576, 128);
                Controls.Add(listCard);

                targets = new ListBox();
                targets.Left = 10;
                targets.Top = 10;
                targets.Width = 556;
                targets.Height = 108;
                targets.BackColor = LauncherTheme.Panel;
                targets.ForeColor = LauncherTheme.Text;
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

                ThemeButton load = new ThemeButton(true);
                load.Text = "Load Moons";
                load.DialogResult = DialogResult.OK;
                load.SetBounds(456, 352, 152, 40);
                Controls.Add(load);
                ThemeButton cancel = new ThemeButton(false);
                cancel.Text = "Cancel";
                cancel.DialogResult = DialogResult.Cancel;
                cancel.SetBounds(338, 352, 106, 40);
                Controls.Add(cancel);
                AcceptButton = load;
                CancelButton = cancel;
            }

            private void DrawTarget(object sender, DrawItemEventArgs eventArgs)
            {
                if (eventArgs.Index < 0 || eventArgs.Index >= targets.Items.Count) return;
                MinecraftTarget target = targets.Items[eventArgs.Index] as MinecraftTarget;
                string label = target == null ? targets.Items[eventArgs.Index].ToString() : target.Label;
                LauncherTheme.DrawChoice(eventArgs.Graphics, eventArgs.Bounds, label, targets.Font,
                    (eventArgs.State & DrawItemState.Selected) != 0,
                    (eventArgs.State & DrawItemState.Focus) != 0);
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
                        + ". Expected 1.8.9.");
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
                + " is Minecraft 1.8.9. Load was cancelled to avoid loading "
                + "the wrong mappings.\r\n\r\n"
                + "Start the game normally so its command line contains --version, or run "
                + DisplayName + " with --minecraft-version 1.8.9.");
        }

        private static string NormalizeConfiguredVersion(string configured)
        {
            return String.Equals(configured == null ? "" : configured.Trim(), "1.8.9", StringComparison.Ordinal)
                ? "1.8.9" : null;
        }

        private static string MatchSupportedVersion(string evidence)
        {
            if (String.IsNullOrWhiteSpace(evidence)) return null;
            // An explicit launch version takes precedence over an incidental cache path.
            Match argument = Regex.Match(evidence,
                "(?:^|\\s)--version(?:=|\\s+)(?:\"([^\"]+)\"|'([^']+)'|([^\\s]+))",
                RegexOptions.IgnoreCase | RegexOptions.CultureInvariant);
            if (argument.Success)
                return NormalizeConfiguredVersion(argument.Groups[1].Success ? argument.Groups[1].Value
                    : argument.Groups[2].Success ? argument.Groups[2].Value : argument.Groups[3].Value);
            return Regex.IsMatch(evidence,
                @"(?<![0-9A-Za-z_.\-])1\.8\.9(?=\.jar(?:$|[^0-9A-Za-z_.\-])|$|[^0-9A-Za-z_.\-])",
                RegexOptions.IgnoreCase | RegexOptions.CultureInvariant) ? "1.8.9" : null;
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
            return DependencyRuntime.ResolveHome();
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
                string jsonConfig = Path.Combine(ResolveHome(), "config", "profiles", "default.json");
                if (File.Exists(jsonConfig))
                {
                    var serializer = new System.Web.Script.Serialization.JavaScriptSerializer();
                    serializer.MaxJsonLength = 2000000;
                    var root = serializer.DeserializeObject(File.ReadAllText(jsonConfig)) as Dictionary<string, object>;
                    object format;
                    if (root != null && root.TryGetValue("format", out format) && Convert.ToInt32(format) == 2)
                    {
                        object valuesObject;
                        object name;
                        var values = root.TryGetValue("values", out valuesObject)
                            ? valuesObject as Dictionary<string, object> : null;
                        return values != null && values.TryGetValue("client.name", out name)
                            ? NormalizeDisplayName(Convert.ToString(name)) ?? DefaultDisplayName
                            : DefaultDisplayName;
                    }
                }
                // Read-only compatibility before the client migrates its legacy settings.
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

        private static string ExtractPayload(string home, string version)
        {
            if (String.Equals(version, "1.8.9", StringComparison.Ordinal))
                return ExtractResource(home, Payload189Resource, "moons-1.8.9.jar");
            throw new InvalidOperationException("No embedded payload for Minecraft " + version + ".");
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
                CacheMaintenance.Touch(target);
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

            using (LoadSession session = LoadSession.Acquire(pid, cancelled))
            {
                LoadBridgeLocked(bridge, payload, bootstrapApi, home, hardwareId,
                    pid, session, progress, cancelled);
            }
        }

        private static void LoadBridgeLocked(
            string bridge,
            string payload,
            string bootstrapApi,
            string home,
            string hardwareId,
            int pid,
            LoadSession session,
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
                string dataDirectory = Path.Combine(
                    Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), ".moons");
                session.Prepare(dataDirectory, payload, bootstrapApi, home, DisplayName, hardwareId);

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
                session.WaitForReady(processHandle);
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
