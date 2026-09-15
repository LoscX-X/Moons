using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.Drawing;
using System.IO;
using System.Net;
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
                try { Install(arguments, null, null, delegate { return false; }); return 0; }
                catch (Exception error) { Console.Error.WriteLine(error); return 1; }
            }
            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);
            using (var form = new InstallerForm(arguments))
            {
                Application.Run(form);
                return form.ExitCode;
            }
        }

        private static void Install(string[] arguments, Action<int, string> progress,
            Action<int, string> downloadProgress, Func<bool> cancelled)
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
                    EnsureUiRuntime(home, arguments, progress, downloadProgress, cancelled);
                    ThrowIfCancelled(cancelled);
                    if (progress != null) progress(35, "Updating YSM libraries and game adapters");
                    YsmPackage.Install(home, DependencyRuntime.ReadResourceBytes("Moons.Ysm.zip"));
                    DependencyRuntime.PublishUiRuntime(home);
                    DependencyRuntime.Verify(home);
                    DependencyRuntime.RecordInstalledVersion(home);
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

            internal InstallerForm(string[] arguments)
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
                    Install(arguments,
                        (percent, message) => worker.ReportProgress(percent, message),
                        (percent, message) => worker.ReportProgress(6 + percent / 10, message),
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

        private static string OptionArgument(string[] arguments, string name)
        {
            for (int index = 0; index + 1 < arguments.Length; index++)
                if (String.Equals(arguments[index], name, StringComparison.OrdinalIgnoreCase))
                    return arguments[index + 1];
            return null;
        }

        private static void ThrowIfCancelled(Func<bool> cancelled)
        {
            if (cancelled()) throw new OperationCanceledException("Dependency installation cancelled.");
        }

        private static string EnsureUiRuntime(
            string home,
            string[] arguments,
            Action<int, string> progress,
            Action<int, string> downloadProgress,
            Func<bool> cancelled)
        {
            Dictionary<string, string> metadata = RuntimeMetadata.Parse(
                Encoding.UTF8.GetString(DependencyRuntime.ReadResourceBytes(DependencyRuntime.UiMetadataResource)));
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
                    if (!CopyLocalUiRuntime(temporary, cancelled))
                    {
                        string url = ResolveUiRuntimeUrl(home, arguments, metadata);
                        if (String.IsNullOrWhiteSpace(url))
                        {
                            throw new InvalidOperationException(
                                "The UI runtime is not cached and no download URL is configured.\r\n\r\n"
                                + "Place moons-ui-runtime.jar beside moon-install.exe, or set --ui-dependency-url, MOONS_UI_DOWNLOAD_URL, "
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

            if (progress != null) progress(17, "UI runtime dependencies are ready");
            return target;
        }

        private static bool CopyLocalUiRuntime(string target, Func<bool> cancelled)
        {
            string directory = AppDomain.CurrentDomain.BaseDirectory;
            foreach (string candidate in new[] {
                Path.Combine(directory, "moons-ui-runtime.jar"),
                Path.Combine(directory, "dependencies", "moons-ui-runtime.jar") })
            {
                if (!File.Exists(candidate)) continue;
                ThrowIfCancelled(cancelled);
                File.Copy(candidate, target, true);
                ThrowIfCancelled(cancelled);
                return true;
            }
            return false;
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

    }
}
