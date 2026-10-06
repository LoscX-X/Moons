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
            int retire = Array.IndexOf(arguments, "--retire-version");
            if (retire >= 0) {
                try {
                    if (retire + 1 >= arguments.Length) throw new ArgumentException("--retire-version requires a version.");
                    string version = arguments[retire + 1];
                    var profile = VersionCatalog.FindArtifact(VersionCatalog.Normalize(version));
                    bool completed = DependencyRuntime.Retire(DependencyRuntime.ResolveHome(), profile == null ? version : profile.GameId);
                    Console.WriteLine(completed ? "Version dependencies moved to legacy unchanged." : "Retirement deferred: a Java process or dependency update is active, or the version is not installed.");
                    return completed ? 0 : 2;
                } catch (Exception error) { Console.Error.WriteLine(error.Message); return 1; }
            }
            if (Array.IndexOf(arguments, "--install-only") >= 0)
            {
                try {
                    string frozen = Option(arguments, "--legacy-installer");
                    Install(SelectedProfile(arguments), null, delegate { return false; },
                        frozen == null ? null : Assembly.LoadFile(Path.GetFullPath(frozen)));
                    return 0;
                }
                catch (Exception error) { Console.Error.WriteLine(error); return 1; }
            }
            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);
            SupportProfile selected;
            try { selected = SelectedProfile(arguments); }
            catch (Exception error) { Console.Error.WriteLine(error.Message); return 1; }
            string sourceInstaller = Option(arguments, "--legacy-installer");
            if (sourceInstaller != null) {
                Console.Error.WriteLine("Use --install-only with --legacy-installer to install a frozen legacy package."); return 1;
            }
            string snapshot = Option(arguments, "--self-test-ui-snapshot");
            if (snapshot != null)
                return LauncherTheme.SaveSnapshot(new InstallerForm(selected), snapshot);
            string menuSnapshot = Option(arguments, "--self-test-menu-ui-snapshot");
            if (menuSnapshot != null)
                using (var form = new InstallerForm(selected)) return form.SaveMenuSnapshot(menuSnapshot);
            using (var form = new InstallerForm(selected))
            {
                Application.Run(form);
                return form.ExitCode;
            }
        }

        private static SupportProfile SelectedProfile(string[] arguments)
        {
            int index = Array.IndexOf(arguments, "--minecraft-version");
            if (index < 0) {
                if (Option(arguments, "--legacy-installer") != null) throw new ArgumentException("A frozen legacy installer requires --minecraft-version.");
                return VersionCatalog.BaseProfile;
            }
            if (index + 1 >= arguments.Length) throw new ArgumentException("--minecraft-version requires a version.");
            if (Option(arguments, "--legacy-installer") != null) {
                string game = arguments[index + 1];
                if (!Regex.IsMatch(game, "^[0-9]+(?:\\.[0-9]+)+(?:-(?:snapshot|pre|rc)-[0-9]+)?$"))
                    throw new ArgumentException("Invalid legacy Minecraft version.");
                int minimum;
                string java = Option(arguments, "--java-minimum") ?? (game == "1.8.9" ? "8" : "25");
                if (!Int32.TryParse(java, out minimum) || minimum < 8 || minimum > 99) throw new ArgumentException("Invalid legacy Java version.");
                return new SupportProfile(game.Replace('.', '_').Replace('-', '_'), game, game, "legacy", "", "^$", new[] { game }, minimum);
            }
            var profile = VersionCatalog.FindArtifact(VersionCatalog.Normalize(arguments[index + 1]));
            if (profile == null) throw new ArgumentException("This installer does not contain dependencies for " + arguments[index + 1] + ". Use its matching legacy installer.");
            return profile;
        }

        private static string Option(string[] arguments, string name)
        {
            int index = Array.IndexOf(arguments, name);
            if (index < 0) return null;
            if (index + 1 >= arguments.Length || arguments[index + 1].StartsWith("--")) throw new ArgumentException(name + " requires a value.");
            return arguments[index + 1];
        }

        private static void Install(SupportProfile profile, Action<int, string> progress, Func<bool> cancelled, Assembly frozen = null)
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
                    Assembly bundle = frozen ?? Assembly.GetExecutingAssembly();
                    var ui = RuntimeMetadata.Parse(Encoding.UTF8.GetString(DependencyRuntime.ReadResourceBytes(bundle, DependencyRuntime.UiMetadataResource)));
                    var ysm = RuntimeMetadata.Parse(Encoding.UTF8.GetString(DependencyRuntime.ReadResourceBytes(bundle, DependencyRuntime.YsmMetadataResource)));
                    foreach (string name in ysm.Keys) if (!Regex.IsMatch(name, "^(libraries|modules)/moons-ysm-[A-Za-z0-9._-]+\\.jar$"))
                        throw new InvalidDataException("Invalid dependency path in source installer.");
                    foreach (string name in DependencyRuntime.RequiredYsm(profile)) if (!ysm.ContainsKey(name))
                        throw new InvalidDataException("The source installer does not contain dependencies for " + profile.GameId + ".");
                    if (frozen != null && ysm.Count != DependencyRuntime.RequiredYsm(profile).Length)
                        throw new InvalidDataException("Import requires an independent legacy dependency package. Retire newer multi-version packages using --retire-version instead.");
                    string root = DependencyRuntime.SelectedRoot(home, profile, ui, ysm);
                    DependencyRuntime.CheckSelectedIdentity(root, profile, ui, ysm);
                    EnsureUiRuntime(home, root, progress, cancelled, bundle);
                    ThrowIfCancelled(cancelled);
                    if (progress != null) progress(35, "Updating YSM libraries and game adapters");
                    var expected = new string[ysm.Count]; ysm.Keys.CopyTo(expected, 0);
                    YsmPackage.Install(root, DependencyRuntime.ReadResourceBytes(bundle, "Moons.Ysm.zip"), DependencyRuntime.RequiredYsm(profile), expected, ysm, home);
                    foreach (string name in DependencyRuntime.RequiredYsm(profile)) {
                        string target = Path.Combine(root, name);
                        string hash = ysm[name].ToLowerInvariant();
                        byte[] bytes = File.ReadAllBytes(target);
                        DependencyRuntime.PublishObject(home, target, hash, temporary => File.WriteAllBytes(temporary, bytes));
                    }
                    DependencyRuntime.PublishSelected(home, profile, ui, ysm);
                    string details = Encoding.UTF8.GetString(DependencyRuntime.ReadResourceBytes(bundle, DependencyRuntime.ReleaseMetadataResource));
                    DependencyRuntime.RecordInstalledVersion(root, details, ysm);
                    DependencyRuntime.Resolve(home, profile, ui, ysm);
                    if (frozen != null) {
                        DependencyRuntime.PrepareFrozenHome(home, root, profile, ui, ysm);
                        Console.WriteLine("Legacy MOONS_HOME=" + root);
                    }
                    CacheMaintenance.Run(home);
                    if (progress != null) progress(100, "Dependencies are up to date. You can now run moon.exe.");
                }
                finally { if (acquired) mutex.ReleaseMutex(); }
            }
        }

        private sealed class InstallerForm : ThemeForm
        {
            private readonly BackgroundWorker worker = new BackgroundWorker();
            private readonly Label status = new Label();
            private readonly ThemeProgressBar progress = new ThemeProgressBar();
            private readonly ThemeButton close = new ThemeButton(false);
            private readonly ThemeButton install = new ThemeButton(true);
            private readonly ThemeVersionSelector selection = new ThemeVersionSelector();
            private readonly Label percentage;
            internal int ExitCode { get; private set; }

            internal int SaveMenuSnapshot(string outputPath) { return selection.SaveMenuSnapshot(outputPath); }

            internal InstallerForm(SupportProfile selected) : base("Moons")
            {
                Text = "Moons Dependency Installer";
                ClientSize = new Size(640, 500);
                InstallChrome(true, "CLIENT / INSTALLER");
                Label eyebrow = LauncherTheme.Label(this, "MOONS / SETUP",
                    new Rectangle(32, 104, 576, 20), 8F, false, LauncherTheme.Muted);
                Label title = LauncherTheme.Label(this, "Set up your client.",
                    new Rectangle(30, 130, 578, 48), 28F, true, LauncherTheme.Text);
                LauncherTheme.Label(this, "Choose Minecraft. Install once, then load Moons.",
                    new Rectangle(32, 185, 576, 26), 10F, false, LauncherTheme.Muted);
                EnableDrag(eyebrow);
                EnableDrag(title);
                GlassPanel card = new GlassPanel();
                card.SetBounds(32, 230, 576, 158);
                Controls.Add(card);
                LauncherTheme.Label(card, "MINECRAFT VERSION",
                    new Rectangle(24, 17, 450, 20), 7.5F, false, LauncherTheme.Muted);
                selection.SetBounds(24, 44, 528, 36);
                foreach (var profile in VersionCatalog.Profiles) selection.Items.Add(profile.GameId);
                selection.SelectedIndex = Array.IndexOf(VersionCatalog.Profiles, selected);
                if (selection.SelectedIndex < 0) selection.SelectedIndex = 0;
                card.Controls.Add(selection);
                status.SetBounds(24, 96, 462, 24);
                status.Text = "Select the Minecraft version to install.";
                status.BackColor = Color.Transparent;
                status.ForeColor = LauncherTheme.Text;
                status.AutoEllipsis = true;
                percentage = LauncherTheme.Label(card, "0%",
                    new Rectangle(490, 96, 62, 24), 9F, false, LauncherTheme.Muted);
                percentage.TextAlign = ContentAlignment.TopRight;
                progress.SetBounds(24, 132, 528, 6);
                card.Controls.AddRange(new Control[] { status, progress });
                close.SetBounds(338, 409, 106, 40);
                close.Text = "Close";
                close.Enabled = true;
                close.Click += delegate { Close(); };
                Controls.Add(close);
                install.SetBounds(456, 409, 152, 40);
                install.Text = "Install dependencies";
                install.Click += delegate {
                    selection.Enabled = install.Enabled = close.Enabled = false;
                    progress.Value = 0;
                    percentage.Text = "0%";
                    status.Text = "Preparing installation...";
                    worker.RunWorkerAsync(VersionCatalog.Profiles[selection.SelectedIndex]);
                };
                Controls.Add(install);
                AcceptButton = install;
                CancelButton = close;
                LauncherTheme.Label(this, DependencyRuntime.VersionLabel().Split('|')[0].Trim(),
                    new Rectangle(32, 471, 350, 20), 8F, false, LauncherTheme.Soft);
                Label footer = LauncherTheme.Label(this, "FREE & OPEN SOURCE",
                    new Rectangle(403, 471, 205, 20), 7.5F, false, LauncherTheme.Soft);
                footer.TextAlign = ContentAlignment.TopRight;
                worker.WorkerReportsProgress = true;
                worker.WorkerSupportsCancellation = true;
                worker.DoWork += delegate(object sender, DoWorkEventArgs e)
                {
                    Install((SupportProfile)e.Argument, (percent, message) => worker.ReportProgress(percent, message),
                        () => worker.CancellationPending);
                };
                worker.ProgressChanged += delegate(object sender, ProgressChangedEventArgs e)
                {
                    progress.Value = Math.Max(progress.Value, Math.Min(100, e.ProgressPercentage));
                    percentage.Text = ((int)progress.Value).ToString() + "%";
                    status.Text = (string)e.UserState;
                };
                worker.RunWorkerCompleted += delegate(object sender, RunWorkerCompletedEventArgs e)
                {
                    ExitCode = e.Error == null ? 0 : 1;
                    if (e.Error != null) {
                        status.Text = "Installation failed. You can try again.";
                        LauncherTheme.ShowError(this, "Unable to install.", e.Error.Message);
                    }
                    close.Enabled = true;
                    selection.Enabled = install.Enabled = true;
                };
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

        private static void EnsureUiRuntime(string home, string root, Action<int, string> progress, Func<bool> cancelled, Assembly bundle)
        {
            var metadata = RuntimeMetadata.Parse(Encoding.UTF8.GetString(DependencyRuntime.ReadResourceBytes(bundle, DependencyRuntime.UiMetadataResource)));
            string expectedHash;
            string sizeText;
            long expectedSize;
            if (!metadata.TryGetValue("sha256", out expectedHash)
                || !Regex.IsMatch(expectedHash, "^[0-9a-fA-F]{64}$")
                || !metadata.TryGetValue("size", out sizeText)
                || !Int64.TryParse(sizeText, out expectedSize) || expectedSize <= 0L)
                throw new InvalidDataException("The embedded UI runtime metadata is invalid.");
            expectedHash = expectedHash.ToLowerInvariant();
            string directory = Path.Combine(root, "ui");
            string target = Path.Combine(directory, "moons-ui-runtime.jar");
            ThrowIfCancelled(cancelled);
            if (!IsExpectedFile(target, expectedHash, expectedSize))
            {
                Directory.CreateDirectory(directory);
                if (progress != null) progress(6, "Extracting embedded UI runtime");
                DependencyRuntime.PublishObject(home, target, expectedHash, temporary =>
                {
                    using (Stream source = bundle.GetManifestResourceStream("Moons.UiRuntime.jar"))
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
