using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Security;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;
using Moons.Shared;

namespace Moons.WindowsLauncher
{
    /// <summary>Retains the latest successful run, never removes user data or live JVM files.</summary>
    internal static class CacheMaintenance
    {
        private const long MiB = 1024L * 1024L;
        private static readonly TimeSpan Grace = TimeSpan.FromMinutes(10);
        private static readonly Regex Hash = new Regex("^[0-9a-f]{64}$");

        internal static bool Run(string home)
        {
            try
            {
                home = Path.GetFullPath(home);
                string identity = Hashing.Sha256(Encoding.UTF8.GetBytes(home.TrimEnd(
                    Path.DirectorySeparatorChar).ToUpperInvariant()));
                using (var mutex = new Mutex(false, "Local\\Moons.DependencyInstall." + identity))
                {
                    bool acquired = false;
                    try
                    {
                        try { acquired = mutex.WaitOne(0); }
                        catch (AbandonedMutexException) { acquired = true; }
                        if (!acquired || JavaRunning()) return false;
                        Prune(home, Path.Combine(Path.GetTempPath(), "moons"), JavaRunning);
                        return !JavaRunning();
                    }
                    finally { if (acquired) mutex.ReleaseMutex(); }
                }
            }
            catch (IOException) { }
            catch (UnauthorizedAccessException) { }
            catch (SecurityException) { }
            return false;
        }

        internal static bool JavaRunning()
        {
            // Even an unrecognized JVM may hold a lazy classloader. Defer until it exits.
            foreach (string name in new[] { "java", "javaw" })
            {
                var processes = Process.GetProcessesByName(name);
                bool running = false;
                foreach (var process in processes) {
                    try {
                        if (!IsBuildJvm(ProcessEvidenceCache.CommandLine(process.Id))) running = true;
                    } finally { process.Dispose(); }
                }
                if (running) return true;
            }
            return false;
        }

        internal static bool IsBuildJvm(string commandLine)
        {
            // Only known compiler/Gradle main classes are exempt; unknown Java remains protected.
            return !String.IsNullOrWhiteSpace(commandLine) && Regex.IsMatch(commandLine,
                @"(?:^|\s)(?:org\.gradle\.launcher\.daemon\.bootstrap\.GradleDaemon|org\.gradle\.wrapper\.GradleWrapperMain|org\.gradle\.launcher\.GradleMain|org\.jetbrains\.kotlin\.daemon\.KotlinCompileDaemon)(?:\s|$)");
        }

        internal static void Prune(string home, string legacyTemp, Func<bool> busy)
        {
            if (busy()) return;
            CacheRun run = ReadRun(home);
            string libraries = Path.Combine(home, "libraries");
            string current = null;
            string pointer = Path.Combine(libraries, "moons-ui-runtime.current");
            if (Safe(home, pointer) && File.Exists(pointer))
            {
                string value = File.ReadAllText(pointer).Trim();
                if (Regex.IsMatch(value, "^[0-9a-f]{64}/moons-ui-runtime.jar$"))
                    current = Path.Combine(libraries, value.Substring(0, 64));
            }
            // An unreadable/missing pointer must not make every UI version eligible for deletion.
            // Installation views retain old releases. An unreadable manifest cannot authorize GC.
            var retained = RetainedUi(home);
            if (current != null && retained != null) Trim(libraries, "ui", 2, 256 * MiB, 30, current, busy, retained);
            // An invalid receipt cannot authorize deletion. Installed legacy bytes are not caches.
            if (run != null) {
                Trim(Path.Combine(home, "cache", "launcher"), "launcher", 0, 0, 0, null, busy, run.Launcher);
                Trim(Path.Combine(home, "cache", "modules"), "modules", 0, 0, 0, null, busy,
                    run.Modules);
                Trim(Path.Combine(home, "cache", "runtime"), "runtime", 0, 0, 0, null, busy,
                    run.Runtime);
            }
            Trim(Path.Combine(home, "cache", "ysm-install"), "backup", 0, 0, 0, null, busy);
            Trim(legacyTemp, "runtime", 0, 0, 0, null, busy);
        }

        internal static void RecordRun(string home, string version, DateTime started, params string[] launcherFiles)
        {
            string root = Path.Combine(home, "cache", "launcher");
            var content = new StringBuilder("format=2\nprofile=" + version + "\nstarted=" + started.ToUniversalTime().Ticks + "\n");
            var seen = new HashSet<string>(StringComparer.Ordinal);
            foreach (string file in launcherFiles) {
                string directory = Path.GetDirectoryName(file);
                string hash = Path.GetFileName(directory);
                if (!Safe(root, file) || !Hash.IsMatch(hash) || ReadEntry(root, directory, "launcher") == null)
                    throw new InvalidDataException("Invalid latest-run launcher cache path.");
                if (seen.Add(hash)) content.Append("launcher.").Append(seen.Count).Append('=').Append(hash).Append('\n');
            }
            int count = 0;
            foreach (string path in UsedSince(Path.Combine(home, "cache", "modules"), "modules", started))
                if (Regex.IsMatch(Path.GetFileName(path), "^(?:[0-9a-f]{64}\\.jar|builtin-core-features\\.jar)$"))
                    content.Append("module.").Append(++count).Append('=').Append(Path.GetFileName(path)).Append('\n');
            count = 0;
            foreach (string path in UsedSince(Path.Combine(home, "cache", "runtime"), "runtime", started))
                content.Append("runtime.").Append(++count).Append('=').Append(Path.GetFileName(path)).Append('\n');
            string pointer = Path.Combine(home, "cache", "last-run.properties");
            if (!Safe(home, pointer)) throw new IOException("Latest-run receipt path contains a directory link.");
            Directory.CreateDirectory(Path.GetDirectoryName(pointer));
            StagedFile.Write(pointer, temporary => File.WriteAllText(temporary, content.ToString(), new UTF8Encoding(false)));
        }

        private static CacheRun ReadRun(string home)
        {
            string pointer = Path.Combine(home, "cache", "last-run.properties");
            if (!Safe(home, pointer)) return null;
            if (!File.Exists(pointer)) return new CacheRun { Started = DateTime.MaxValue };
            try {
                var values = RuntimeMetadata.Parse(File.ReadAllText(pointer));
                string format, started, profile;
                long ticks;
                if (!values.TryGetValue("format", out format) || format != "2"
                    || !values.TryGetValue("profile", out profile)
                    || !Regex.IsMatch(profile, "^[0-9]+(?:\\.[0-9]+)+(?:-(?:snapshot|pre|rc)-[0-9]+)?$")
                    || !values.TryGetValue("started", out started) || !Int64.TryParse(started, out ticks)
                    || ticks <= 0 || ticks > DateTime.UtcNow.Ticks) return null;
                var result = new CacheRun { Started = new DateTime(ticks, DateTimeKind.Utc) };
                foreach (var pair in values) if (pair.Key.StartsWith("launcher.", StringComparison.Ordinal)) {
                    if (!Hash.IsMatch(pair.Value)) return null;
                    result.Launcher.Add(Path.Combine(home, "cache", "launcher", pair.Value));
                } else if (pair.Key.StartsWith("module.", StringComparison.Ordinal)) {
                    if (!Regex.IsMatch(pair.Value, "^(?:[0-9a-f]{64}\\.jar|builtin-core-features\\.jar)$")) return null;
                    result.Modules.Add(Path.Combine(home, "cache", "modules", pair.Value));
                } else if (pair.Key.StartsWith("runtime.", StringComparison.Ordinal)) {
                    if (!Hash.IsMatch(pair.Value)) return null;
                    result.Runtime.Add(Path.Combine(home, "cache", "runtime", pair.Value));
                }
                return result.Launcher.Count == 0 ? null : result;
            } catch (IOException) { return null; }
            catch (UnauthorizedAccessException) { return null; }
        }

        private static ISet<string> UsedSince(string root, string kind, DateTime started)
        {
            var retained = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
            if (!Directory.Exists(root) || !Safe(root, root)) return retained;
            foreach (string path in Directory.GetFileSystemEntries(root)) {
                Entry entry = ReadEntry(root, path, kind);
                if (entry != null && entry.Modified >= started) retained.Add(entry.Path);
            }
            return retained;
        }

        private sealed class CacheRun
        {
            internal DateTime Started;
            internal readonly ISet<string> Launcher = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
            internal readonly ISet<string> Modules = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
            internal readonly ISet<string> Runtime = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        }

        private static ISet<string> RetainedUi(string home)
        {
            var retained = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
            string root = Path.Combine(home, "installations");
            if (!Directory.Exists(root)) return retained;
            try {
                if (!Safe(home, root)) return null;
                foreach (string view in Directory.GetDirectories(root)) {
                    if (!Safe(home, view)) return null;
                    string contexts = Path.Combine(view, "contexts");
                    if (!Safe(home, contexts) || !Directory.Exists(contexts)) return null;
                    foreach (string file in Directory.GetFiles(contexts, "*.properties")) {
                        if (!Safe(home, file)) return null;
                        string ui;
                        if (!RuntimeMetadata.Parse(File.ReadAllText(file)).TryGetValue("ui", out ui)
                            || !Regex.IsMatch(ui, "^libraries/[0-9a-f]{64}/moons-ui-runtime.jar$")) return null;
                        retained.Add(Path.Combine(home, "libraries", ui.Split('/')[1]));
                    }
                }
                return retained;
            } catch (IOException) { return null; }
            catch (UnauthorizedAccessException) { return null; }
        }

        internal static void Touch(string path)
        {
            try { File.SetLastWriteTimeUtc(path, DateTime.UtcNow); }
            catch (IOException) { }
            catch (UnauthorizedAccessException) { }
        }

        internal static void DiscardCompletedBackup(string home, string path)
        {
            try
            {
                string root = Path.Combine(home, "cache", "ysm-install");
                Entry entry = ReadEntry(root, path, "backup");
                if (entry != null) Remove(root, entry);
            }
            catch (IOException) { }
            catch (UnauthorizedAccessException) { }
        }

        internal static void Trim(string root, string kind, int countLimit, long byteLimit,
            int days, string keep, Func<bool> busy, ISet<string> retained = null)
        {
            if (busy() || !Directory.Exists(root) || !Safe(root, root)) return;
            var entries = new List<Entry>();
            long bytes = 0;
            foreach (string path in Directory.GetFileSystemEntries(root))
            {
                Entry entry = ReadEntry(root, path, kind);
                if (entry == null) continue;
                entries.Add(entry);
                bytes += entry.Bytes;
            }
            int count = entries.Count;
            entries.Sort((left, right) => left.Modified.CompareTo(right.Modified));
            DateTime now = DateTime.UtcNow;
            foreach (Entry entry in entries)
            {
                if (busy()) return;
                if (String.Equals(entry.Path, keep, StringComparison.OrdinalIgnoreCase)
                    || retained != null && retained.Contains(entry.Path)
                    || now - entry.Modified < Grace) continue;
                if (count <= countLimit && bytes <= byteLimit && now - entry.Modified < TimeSpan.FromDays(days)) continue;
                if (Remove(root, entry)) { count--; bytes -= entry.Bytes; }
            }
        }

        private static Entry ReadEntry(string root, string path, string kind)
        {
            if (!Safe(root, path)) return null;
            string name = Path.GetFileName(path);
            bool directory = Directory.Exists(path);
            if (kind == "modules")
            {
                if (directory || !Regex.IsMatch(name, "^(?:[0-9a-f]{64}(?:\\.jar|\\.tmp-[0-9]+)|builtin-core-features\\.jar)$")) return null;
            }
            else if (!directory || (kind == "backup" ? !Regex.IsMatch(name, "^[0-9a-f]{32}$") : !Hash.IsMatch(name))) return null;
            var entry = new Entry { Path = path, Modified = Directory.Exists(path)
                ? Directory.GetLastWriteTimeUtc(path) : File.GetLastWriteTimeUtc(path) };
            return Collect(root, path, path, kind, entry) ? entry : null;
        }

        private static bool Collect(string root, string top, string path, string kind, Entry entry)
        {
            if (!Safe(root, path)) return false;
            if (Directory.Exists(path))
            {
                if (path != top && (kind != "backup" || Path.GetDirectoryName(path) != top
                    || (Path.GetFileName(path) != "libraries" && Path.GetFileName(path) != "modules"))) return false;
                foreach (string child in Directory.GetFileSystemEntries(path))
                    if (!Collect(root, top, child, kind, entry)) return false;
                entry.Directories.Add(path);
                return true;
            }
            string name = Path.GetFileName(path);
            if (kind == "ui" && !Regex.IsMatch(name, "^moons-ui-runtime\\.jar(?:\\.tmp-[0-9]+)?$")) return false;
            if (kind == "runtime" && !Regex.IsMatch(name, "^moons-runtime\\.jar(?:\\.tmp-[0-9]+)?$")) return false;
            if (kind == "launcher" && !Regex.IsMatch(name, "^moons-(?:[0-9]+(?:\\.[0-9]+)+(?:-(?:snapshot|pre|rc)-[0-9]+)?\\.jar|api\\.jar|bridge\\.dll)(?:\\.tmp-[0-9]+)?$")) return false;
            if (kind == "backup")
            {
                string relative = path.Substring(top.Length + 1).Replace('\\', '/');
                if (!Regex.IsMatch(relative, "^(?:libraries/moons-ysm-(?:core|codecs|images)|modules/moons-ysm-[0-9]+(?:\\.[0-9]+)+(?:-(?:snapshot|pre|rc)-[0-9]+)?)\\.jar$")) return false;
            }
            var file = new FileInfo(path);
            entry.Files.Add(path);
            entry.Bytes += file.Length;
            if (file.LastWriteTimeUtc > entry.Modified) entry.Modified = file.LastWriteTimeUtc;
            return true;
        }

        private static bool Remove(string root, Entry entry)
        {
            var held = new List<FileStream>();
            try
            {
                // Recheck every path and reserve every file before deleting anything in a group.
                foreach (string directory in entry.Directories) if (!Safe(root, directory)) return false;
                foreach (string file in entry.Files)
                {
                    if (!Safe(root, file)) return false;
                    held.Add(new FileStream(file, FileMode.Open, FileAccess.Read, FileShare.Delete));
                }
                foreach (string file in entry.Files) File.Delete(file);
                foreach (var stream in held) stream.Dispose();
                held.Clear();
                foreach (string directory in entry.Directories) Directory.Delete(directory, false);
                return true;
            }
            catch (IOException) { return false; }
            catch (UnauthorizedAccessException) { return false; }
            finally { foreach (var stream in held) stream.Dispose(); }
        }

        internal static bool Safe(string root, string path)
        {
            root = Path.GetFullPath(root).TrimEnd(Path.DirectorySeparatorChar);
            path = Path.GetFullPath(path);
            if (!path.Equals(root, StringComparison.OrdinalIgnoreCase)
                && !path.StartsWith(root + Path.DirectorySeparatorChar, StringComparison.OrdinalIgnoreCase)) return false;
            for (string part = path; !String.IsNullOrEmpty(part); part = Path.GetDirectoryName(part))
                if ((File.Exists(part) || Directory.Exists(part))
                    && (File.GetAttributes(part) & FileAttributes.ReparsePoint) != 0) return false;
            return true;
        }

        private sealed class Entry
        {
            internal string Path;
            internal DateTime Modified;
            internal long Bytes;
            internal readonly List<string> Files = new List<string>();
            internal readonly List<string> Directories = new List<string>();
        }
    }
}
