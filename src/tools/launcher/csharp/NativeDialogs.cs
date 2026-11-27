using System;
using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.Reflection;
using System.Text;
using System.Text.RegularExpressions;
using System.Windows.Forms;

namespace Tempos.WindowsLauncher
{
    // Runs in the embedded helper because Minecraft explicitly disables in-process AWT dialogs.
    internal static class NativeDialogs
    {
        internal static bool TryRun(string[] args, out int result)
        {
            result = 0;
            if (args.Length == 0 || args[0] != "--native-dialog") return false;
            try { result = Run(args); }
            catch (Exception failure) {
                Console.Error.WriteLine(failure.Message);
                result = 1;
            }
            return true;
        }

        private static string Option(string[] args, string name)
        {
            int index = Array.IndexOf(args, name);
            if (index < 0 || index + 1 >= args.Length) return String.Empty;
            return args[index + 1];
        }

        private static int Run(string[] args)
        {
            if (args.Length < 2) throw new ArgumentException("Missing dialog operation.");
            string operation = args[1];
            string initial = Option(args, "--initial");
            string result = Path.GetFullPath(Option(args, "--result"));
            string root = Path.GetDirectoryName(Assembly.GetExecutingAssembly().Location);
            if (!String.Equals(Path.GetDirectoryName(result), root, StringComparison.OrdinalIgnoreCase)
                || !Regex.IsMatch(Path.GetFileName(result), "^\\.dialog-[a-f0-9]{32}\\.txt$"))
                throw new InvalidDataException("Dialog result must stay in its temporary session.");

            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);
            using (var lifetime = MonitorParent(args)) {
                string selected;
                if (operation == "folder") selected = Directory(initial);
                else if (operation == "open-json") selected = Json(initial, false);
                else if (operation == "save-json") selected = Json(initial, true);
                else throw new ArgumentException("Unknown dialog operation.");
                if (selected != null)
                    File.WriteAllText(result, Path.GetFullPath(selected), new UTF8Encoding(false));
            }
            return 0;
        }

        private static string Directory(string initial)
        {
            using (var dialog = new FolderBrowserDialog()) {
                dialog.Description = "Select model folder";
                dialog.ShowNewFolderButton = true;
                if (System.IO.Directory.Exists(initial)) dialog.SelectedPath = Path.GetFullPath(initial);
                return dialog.ShowDialog() == DialogResult.OK ? dialog.SelectedPath : null;
            }
        }

        private static string Json(string initial, bool save)
        {
            using (FileDialog dialog = save ? (FileDialog)new SaveFileDialog() : new OpenFileDialog()) {
                dialog.Title = save ? "Export parameters" : "Import parameters";
                dialog.Filter = "JSON parameters (*.json)|*.json";
                dialog.DefaultExt = "json";
                dialog.AddExtension = true;
                dialog.RestoreDirectory = true;
                if (System.IO.Directory.Exists(initial)) dialog.InitialDirectory = initial;
                else if (!String.IsNullOrWhiteSpace(initial)) {
                    dialog.InitialDirectory = Path.GetDirectoryName(Path.GetFullPath(initial));
                    dialog.FileName = Path.GetFileName(initial);
                }
                if (save && String.IsNullOrEmpty(dialog.FileName)) dialog.FileName = "tempos-parameters.json";
                return dialog.ShowDialog() == DialogResult.OK ? dialog.FileName : null;
            }
        }

        private static System.Windows.Forms.Timer MonitorParent(string[] args)
        {
            int pid;
            long started;
            if (!Int32.TryParse(Option(args, "--parent-pid"), out pid) || pid <= 0) return null;
            Int64.TryParse(Option(args, "--parent-started"), out started);
            var timer = new System.Windows.Forms.Timer { Interval = 300 };
            timer.Tick += (sender, unused) => {
                try {
                    using (var parent = Process.GetProcessById(pid)) {
                        long identity = (parent.StartTime.ToUniversalTime().Ticks
                            - new DateTime(1970, 1, 1, 0, 0, 0, DateTimeKind.Utc).Ticks)
                            / TimeSpan.TicksPerMillisecond;
                        if (parent.HasExited || started > 0 && identity != started) Environment.Exit(0);
                    }
                } catch (ArgumentException) { Environment.Exit(0); }
                catch (InvalidOperationException) { Environment.Exit(0); }
            };
            timer.Start();
            return timer;
        }
    }
}
