using System;
using System.Collections.Generic;
using System.IO;
using System.IO.Compression;
using System.Threading;
using Moons.Shared;

namespace Moons.WindowsLauncher
{
    /// <summary>Installs one shared runtime and all adapters before attaching to a game.</summary>
    internal static class YsmPackage
    {
        internal static readonly string[] Files = {
            "libraries/moons-ysm-core.jar",
            "libraries/moons-ysm-codecs.jar",
            "libraries/moons-ysm-images.jar",
            "modules/moons-ysm-26.1.2.jar",
            "modules/moons-ysm-26.2.jar",
            "modules/moons-ysm-26.3-rc-2.jar"
        };

        internal static void Install(string home, byte[] package)
        {
            // Fully validate the embedded archive before touching the installation.
            var contents = new Dictionary<string, byte[]>(StringComparer.Ordinal);
            using (var memory = new MemoryStream(package, false))
            using (var archive = new ZipArchive(memory, ZipArchiveMode.Read))
            {
                foreach (var entry in archive.Entries)
                {
                    if (ZipEntries.IsDirectory(entry)) continue;
                    if (Array.IndexOf(Files, entry.FullName) < 0
                        || contents.ContainsKey(entry.FullName) || entry.Length == 0)
                        throw new InvalidDataException("Invalid YSM package entry: " + entry.FullName);
                    contents.Add(entry.FullName, ZipEntries.ReadBytes(entry));
                }
            }
            if (contents.Count != Files.Length)
                throw new InvalidDataException("The YSM package is incomplete.");

            home = Path.GetFullPath(home);
            string identity = Hashing.Sha256(System.Text.Encoding.UTF8.GetBytes(
                home.TrimEnd(Path.DirectorySeparatorChar).ToUpperInvariant()));
            using (var mutex = new Mutex(false, "Local\\Moons.YsmInstall." + identity))
            {
                bool acquired = false;
                try
                {
                    try { acquired = mutex.WaitOne(TimeSpan.FromSeconds(30)); }
                    catch (AbandonedMutexException) { acquired = true; }
                    if (!acquired) throw new IOException("Another launcher is updating YSM. Try again shortly.");
                    InstallLocked(home, contents);
                }
                finally { if (acquired) mutex.ReleaseMutex(); }
            }
        }

        private static void InstallLocked(string home, Dictionary<string, byte[]> contents)
        {
            string attempt = Guid.NewGuid().ToString("N");
            string backupRoot = Path.Combine(home, "cache", "ysm-install", attempt);
            var pending = new List<Replacement>();
            var committed = new List<Replacement>();
            try
            {
                foreach (string name in Files)
                {
                    string target = Path.Combine(home, name.Replace('/', Path.DirectorySeparatorChar));
                    byte[] bytes = contents[name];
                    bool existed = File.Exists(target);
                    if (existed && Hashing.Sha256(target) == Hashing.Sha256(bytes)) continue;
                    Directory.CreateDirectory(Path.GetDirectoryName(target));
                    var replacement = new Replacement {
                        Target = target, Temporary = target + ".ysm-" + attempt + ".tmp",
                        Backup = existed ? Path.Combine(backupRoot, name.Replace('/', Path.DirectorySeparatorChar)) : null
                    };
                    pending.Add(replacement);
                    File.WriteAllBytes(replacement.Temporary, bytes);
                    if (existed)
                    {
                        Directory.CreateDirectory(Path.GetDirectoryName(replacement.Backup));
                        File.Copy(target, replacement.Backup);
                    }
                }
                // Each file is published atomically. Libraries precede their consuming modules.
                // Active games own cached JAR copies; their watcher can replace the module later.
                foreach (var replacement in pending)
                {
                    if (replacement.Backup != null)
                        File.Replace(replacement.Temporary, replacement.Target, null);
                    else
                        File.Move(replacement.Temporary, replacement.Target);
                    committed.Add(replacement);
                }
            }
            catch (Exception failure)
            {
                var failures = new List<Exception> { failure };
                for (int index = committed.Count - 1; index >= 0; index--)
                {
                    var replacement = committed[index];
                    try
                    {
                        if (replacement.Backup != null)
                            File.Replace(replacement.Backup, replacement.Target, null);
                        else
                            File.Delete(replacement.Target);
                    }
                    catch (Exception rollback) { failures.Add(rollback); }
                }
                throw new IOException("Could not install the matching YSM libraries and modules. Backups: "
                    + backupRoot, new AggregateException(failures));
            }
            finally
            {
                foreach (var replacement in pending)
                    if (File.Exists(replacement.Temporary)) File.Delete(replacement.Temporary);
            }
        }

        private sealed class Replacement
        {
            internal string Target;
            internal string Temporary;
            internal string Backup;
        }
    }
}
