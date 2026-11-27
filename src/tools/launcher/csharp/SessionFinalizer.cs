using System;
using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.Reflection;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;

namespace Tempos.WindowsLauncher
{
    /// <summary>Collects final parameters after the launcher and game exit, then removes its temporary files.</summary>
    internal static class SessionFinalizer
    {
        [STAThread]
        private static int Main(string[] args)
        {
            int dialogResult;
            if (NativeDialogs.TryRun(args, out dialogResult)) return dialogResult;
            string root = null;
            try {
                if (args.Length != 2 || args[0] != "--session") return 1;
                root = PortableConfig.SessionRoot(args[1]);
                var manifest = PortableConfig.Manifest(root);
                WaitForInstance(Convert.ToInt32(manifest["gamePid"], CultureInfo.InvariantCulture),
                    Int64.Parse((string)manifest["gameStarted"], CultureInfo.InvariantCulture));
                WaitForInstance(Convert.ToInt32(manifest["launcherPid"], CultureInfo.InvariantCulture),
                    Int64.Parse((string)manifest["launcherStarted"], CultureInfo.InvariantCulture));
                Exception last = null;
                for (int attempt = 0; attempt < 40; attempt++) {
                    try { Finish(root); last = null; break; }
                    catch (IOException failure) { last = failure; }
                    catch (UnauthorizedAccessException failure) { last = failure; }
                    Thread.Sleep(250);
                }
                if (last != null) throw last;
                return 0;
            } catch (Exception failure) {
                // Preserve the only up-to-date parameter copy when the EXE cannot be replaced.
                if (root != null && Directory.Exists(root)) {
                    try { File.WriteAllText(Path.Combine(root, "parameter-save-error.txt"), failure.Message, new UTF8Encoding(false)); }
                    catch (IOException) { }
                    catch (UnauthorizedAccessException) { }
                }
                return 1;
            } finally { ScheduleOwnCleanup(); }
        }

        internal static void Finish(string root)
        {
            var manifest = PortableConfig.Manifest(root);
            string lockFile = Path.Combine(root, ".tempos-parameter-commit.lock");
            if (File.Exists(lockFile) && PortableConfig.IsLink(lockFile)) throw new IOException("Linked parameter lock.");
            using (var reservation = new FileStream(lockFile, FileMode.OpenOrCreate, FileAccess.ReadWrite, FileShare.None)) {
                if (!manifest.ContainsKey("committed")) {
                    byte[] parameters = PortableConfig.Collect(root);
                    string hash = PortableConfig.Write((string)manifest["executable"], parameters);
                    LegacyConfigMigration.Complete(manifest["legacy"]);
                    PortableConfig.CompleteRecoveries(manifest);
                    manifest["committed"] = hash;
                    PortableConfig.WriteManifest(root, manifest);
                }
            }
            PortableConfig.DeleteSession(root);
        }

        private static void WaitForInstance(int pid, long started)
        {
            if (pid <= 0 || started <= 0) throw new InvalidDataException("Missing process lifetime identity.");
            Process process;
            try { process = Process.GetProcessById(pid); }
            catch (ArgumentException) { return; }
            using (process) {
                try {
                    if (process.StartTime.ToUniversalTime().Ticks == started) process.WaitForExit();
                } catch (InvalidOperationException) { }
            }
        }

        private static void ScheduleOwnCleanup()
        {
            try {
                string directory = Path.GetDirectoryName(Assembly.GetExecutingAssembly().Location);
                string id = Path.GetFileName(directory);
                string parent = Path.GetFullPath(Path.Combine(Path.GetTempPath(), "tempos-finalizers")).TrimEnd(Path.DirectorySeparatorChar);
                if (!Regex.IsMatch(id, "^[0-9a-f]{32}$")
                    || !String.Equals(Path.GetDirectoryName(directory), parent, StringComparison.OrdinalIgnoreCase)
                    || PortableConfig.IsLink(directory)
                    || !File.Exists(Path.Combine(directory, ".tempos-finalizer"))
                    || File.ReadAllText(Path.Combine(directory, ".tempos-finalizer")) != id) return;
                string script = Path.Combine(directory, "cleanup.ps1");
                File.WriteAllText(script, CleanupScript, new UTF8Encoding(false));
                using (var owner = Process.GetCurrentProcess()) {
                    var start = new ProcessStartInfo("powershell.exe", "-NoProfile -NonInteractive -ExecutionPolicy Bypass -File "
                        + PortableConfig.Quote(script) + " -Id " + id + " -OwnerPid " + owner.Id.ToString(CultureInfo.InvariantCulture)
                        + " -Started " + owner.StartTime.ToUniversalTime().Ticks.ToString(CultureInfo.InvariantCulture)) {
                        UseShellExecute = false, CreateNoWindow = true, WindowStyle = ProcessWindowStyle.Hidden
                    };
                    using (Process cleanup = Process.Start(start)) { }
                }
            } catch (IOException) { }
            catch (UnauthorizedAccessException) { }
            catch (System.ComponentModel.Win32Exception) { }
        }

        private const string CleanupScript = @"param([string]$Id,[int]$OwnerPid,[long]$Started)
$ErrorActionPreference='Stop'
if($Id -notmatch '^[0-9a-f]{32}$'){exit 1}
$parent=[IO.Path]::GetFullPath([IO.Path]::Combine([IO.Path]::GetTempPath(),'tempos-finalizers')).TrimEnd([IO.Path]::DirectorySeparatorChar)
$target=[IO.Path]::GetFullPath([IO.Path]::Combine($parent,$Id))
if([IO.Path]::GetDirectoryName($target) -ne $parent){exit 1}
for($i=0;$i -lt 300;$i++){
  $owner=Get-Process -Id $OwnerPid -ErrorAction SilentlyContinue
  if(!$owner -or $owner.StartTime.ToUniversalTime().Ticks -ne $Started){break}
  Start-Sleep -Milliseconds 100
}
if($i -eq 300){exit 1}
if(!(Test-Path -LiteralPath $target)){exit 0}
if((Get-Item -LiteralPath $target -Force).Attributes -band [IO.FileAttributes]::ReparsePoint){exit 1}
$marker=[IO.Path]::Combine($target,'.tempos-finalizer')
if(!(Test-Path -LiteralPath $marker) -or [IO.File]::ReadAllText($marker) -ne $Id){exit 1}
$pending=New-Object 'System.Collections.Generic.Stack[string]'
$pending.Push($target)
while($pending.Count -gt 0){
  foreach($item in Get-ChildItem -LiteralPath $pending.Pop() -Force){
    if($item.Attributes -band [IO.FileAttributes]::ReparsePoint){exit 1}
    if($item.PSIsContainer){$pending.Push($item.FullName)}
  }
}
Remove-Item -LiteralPath $target -Recurse -Force
";
    }
}
