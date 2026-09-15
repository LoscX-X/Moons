using System;
using System.ComponentModel;
using System.Drawing;
using System.IO;
using System.Reflection;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;
using System.Windows.Forms;
using Moons.Shared;

[assembly: System.Reflection.AssemblyTitle("Moons Dependency Installer")]

namespace Moons.WindowsLauncher
{
    internal static class DependencyInstaller
    {
        [STAThread]
        private static int Main(string[] arguments)
        {
            if (Array.IndexOf(arguments, "--version") >= 0)
            {
                Console.WriteLine(DependencyRuntime.VersionDetails());
                return 0;
            }
            if (Array.IndexOf(arguments, "--install-only") >= 0)
            {
                try { Install(null, delegate { return false; }); return 0; }
                catch (Exception error) { Console.Error.WriteLine(error); return 1; }
            }
            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);
            using (var form = new InstallerForm())
            {
                Application.Run(form);
                return form.ExitCode;
            }
        }

        private static void Install(Action<int, string> progress, Func<bool> cancelled)
        {
            string home = DependencyRuntime.ResolveHome();
            string identity = Hashing.Sha256(Encoding.UTF8.GetBytes(home.TrimEnd(
                Path.DirectorySeparatorChar).ToUpperInvariant()));
            using (var mutex = new Mutex(false, "Local\\Moons.DependencyInstall." + identity))
            {
                bool acquired = false;
                try
                {
                    try { acquired = mutex.WaitOne(0); }
                    catch (AbandonedMutexException) { acquired = true; }
                    if (!acquired) throw new IOException("Another installer is updating these dependencies. Try again shortly.");
                    EnsureUiRuntime(home, progress, cancelled);
                    ThrowIfCancelled(cancelled);
                    if (progress != null) progress(35, "Updating YSM libraries and game adapters");
                    YsmPackage.Install(home, DependencyRuntime.ReadResourceBytes("Moons.Ysm.zip"));
                    DependencyRuntime.PublishUiRuntime(home);
                    DependencyRuntime.Verify(home);
                    DependencyRuntime.RecordInstalledVersion(home);
                    CacheMaintenance.Run(home);
                    if (progress != null) progress(100, "Dependencies are up to date. You can now run moon.exe.");
                }
                finally { if (acquired) mutex.ReleaseMutex(); }
            }
        }

        private sealed class InstallerForm : Form
        {
            private readonly BackgroundWorker worker = new BackgroundWorker();
            private readonly Label status = new Label();
            private readonly ProgressBar progress = new ProgressBar();
            private readonly Button close = new Button();
            internal int ExitCode { get; private set; }

            internal InstallerForm()
            {
                Text = "Moons Dependency Installer";
                ClientSize = new Size(520, 195);
                StartPosition = FormStartPosition.CenterScreen;
                FormBorderStyle = FormBorderStyle.FixedDialog;
                MaximizeBox = false;
                BackColor = Color.FromArgb(24, 24, 24);
                ForeColor = Color.White;
                var versions = new Label();
                versions.SetBounds(20, 12, 480, 25);
                versions.Text = DependencyRuntime.VersionLabel();
                Controls.Add(versions);
                status.SetBounds(20, 45, 480, 60);
                status.Text = "Checking dependencies...";
                progress.SetBounds(20, 115, 480, 18);
                close.SetBounds(400, 150, 100, 28);
                close.Text = "Close";
                close.Enabled = false;
                close.FlatStyle = FlatStyle.Flat;
                close.Click += delegate { Close(); };
                Controls.AddRange(new Control[] { status, progress, close });
                worker.WorkerReportsProgress = true;
                worker.WorkerSupportsCancellation = true;
                worker.DoWork += delegate
                {
                    Install((percent, message) => worker.ReportProgress(percent, message),
                        () => worker.CancellationPending);
                };
                worker.ProgressChanged += delegate(object sender, ProgressChangedEventArgs e)
                {
                    progress.Value = Math.Max(progress.Value, Math.Min(100, e.ProgressPercentage));
                    status.Text = (string)e.UserState;
                };
                worker.RunWorkerCompleted += delegate(object sender, RunWorkerCompletedEventArgs e)
                {
                    ExitCode = e.Error == null ? 0 : 1;
                    if (e.Error != null) status.Text = e.Error.Message;
                    close.Enabled = true;
                };
                Shown += delegate { worker.RunWorkerAsync(); };
                FormClosing += delegate(object sender, FormClosingEventArgs e)
                {
                    if (worker.IsBusy)
                    {
                        worker.CancelAsync();
                        status.Text = "Finishing the current operation...";
                        e.Cancel = true;
                    }
                };
                FormClosed += delegate { worker.Dispose(); };
            }
        }

        private static void ThrowIfCancelled(Func<bool> cancelled)
        {
            if (cancelled()) throw new OperationCanceledException("Dependency installation cancelled.");
        }

        private static void EnsureUiRuntime(string home, Action<int, string> progress, Func<bool> cancelled)
        {
            var metadata = DependencyRuntime.Metadata(DependencyRuntime.UiMetadataResource);
            string expectedHash;
            string sizeText;
            long expectedSize;
            if (!metadata.TryGetValue("sha256", out expectedHash)
                || !Regex.IsMatch(expectedHash, "^[0-9a-fA-F]{64}$")
                || !metadata.TryGetValue("size", out sizeText)
                || !Int64.TryParse(sizeText, out expectedSize) || expectedSize <= 0L)
                throw new InvalidDataException("The embedded UI runtime metadata is invalid.");
            expectedHash = expectedHash.ToLowerInvariant();
            string directory = Path.Combine(home, "libraries", expectedHash);
            string target = Path.Combine(directory, "moons-ui-runtime.jar");
            ThrowIfCancelled(cancelled);
            if (!IsExpectedFile(target, expectedHash, expectedSize))
            {
                Directory.CreateDirectory(directory);
                if (progress != null) progress(6, "Extracting embedded UI runtime");
                StagedFile.Write(target, temporary =>
                {
                    using (Stream source = Assembly.GetExecutingAssembly().GetManifestResourceStream("Moons.UiRuntime.jar"))
                    {
                        if (source == null) throw new InvalidDataException("The installer is missing its UI runtime.");
                        using (FileStream output = new FileStream(temporary, FileMode.Create, FileAccess.Write, FileShare.None))
                        {
                            byte[] buffer = new byte[128 * 1024];
                            long written = 0L;
                            int count;
                            while ((count = source.Read(buffer, 0, buffer.Length)) > 0)
                            {
                                ThrowIfCancelled(cancelled);
                                output.Write(buffer, 0, count);
                                written += count;
                                if (progress != null) progress(6 + (int)Math.Min(24L, written * 24L / expectedSize),
                                    "Extracting embedded UI runtime");
                            }
                        }
                    }
                    ThrowIfCancelled(cancelled);
                    if (!IsExpectedFile(temporary, expectedHash, expectedSize))
                        throw new InvalidDataException("The embedded UI runtime failed its SHA-256 or size check.");
                });
            }
            if (progress != null) progress(30, "UI runtime dependencies are ready");
        }

        private static bool IsExpectedFile(string path, string hash, long size)
        {
            if (!File.Exists(path)) return false;
            FileInfo file = new FileInfo(path);
            return (size <= 0L || file.Length == size)
                && String.Equals(Hashing.Sha256(path), hash, StringComparison.OrdinalIgnoreCase);
        }

    }
}
