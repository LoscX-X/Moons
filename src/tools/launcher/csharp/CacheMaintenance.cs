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
    /// <summary>Best-effort retention of regenerable files, never user data or live JVM caches.</summary>
    internal static class CacheMaintenance
    {
        private const long MiB = 1024L * 1024L;
        private static readonly TimeSpan Grace = TimeSpan.FromMinutes(10);
        private static readonly Regex Hash = new Regex("^[0-9a-f]{64}$");

        internal static void Run(string home)
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
                        if (acquired) Prune(home, Path.Combine(Path.GetTempPath(), "moons"), JavaRunning);
                    }
                    finally { if (acquired) mutex.ReleaseMutex(); }
                }
            }
            catch (IOException) { }
            catch (UnauthorizedAccessException) { }
            catch (SecurityException) { }
        }

        private static bool JavaRunning()
        {
            // Even an unrecognized JVM may hold a lazy classloader. Defer until it exits.
            foreach (string name in new[] { "java", "javaw" })
            {
                var processes = Process.GetProcessesByName(name);
                bool running = processes.Length != 0;
                foreach (var process in processes) process.Dispose();
                if (running) return true;
            }
            return false;
        }

        internal static void Prune(string home, string legacyTemp, Func<bool> busy)
        {
            if (busy()) return;
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
            if (current != null) Trim(libraries, "ui", 2, 256 * MiB, 30, current, busy);
            Trim(Path.Combine(home, "cache", "launcher"), "launcher", 12, 128 * MiB, 30, null, busy);
            Trim(Path.Combine(home, "cache", "modules"), "modules", 32, 256 * MiB, 30, null, busy);
            Trim(Path.Combine(home, "cache", "runtime"), "runtime", 8, 64 * MiB, 30, null, busy);
            Trim(Path.Combine(home, "cache", "ysm-install"), "backup", 2, 64 * MiB, 7, null, busy);
            Trim(legacyTemp, "runtime", 8, 64 * MiB, 7, null, busy);
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
            int days, string keep, Func<bool> busy)
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
                if (directory || !Regex.IsMatch(name, "^[0-9a-f]{64}(?:\\.jar|\\.tmp-[0-9]+)$")) return null;
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
            if (kind == "launcher" && !Regex.IsMatch(name, "^moons-(?:26\\.[123]\\.jar|api\\.jar|bridge\\.dll)(?:\\.tmp-[0-9]+)?$")) return false;
            if (kind == "backup")
            {
                string relative = path.Substring(top.Length + 1).Replace('\\', '/');
                if (Array.IndexOf(DependencyRuntime.YsmPackageNames, relative) < 0) return false;
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

        private static bool Safe(string root, string path)
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
