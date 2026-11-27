using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.IO.Compression;
using System.Reflection;
using System.Text;
using System.Text.RegularExpressions;
using Tempos.Shared;

namespace Tempos.WindowsLauncher
{
    /// <summary>Owns the embedded libraries for one load without installing a persistent library tree.</summary>
    internal sealed class TemporaryPayloadSession : IDisposable
    {
        private const string MarkerName = ".tempos-session";
        private readonly SessionOwner owner;
        private bool retained;
        private long retainedStarted;
        internal readonly string Root;
        internal readonly DependencyRuntime.Context Context;

        private TemporaryPayloadSession(string root, DependencyRuntime.Context context, SessionOwner owner)
        {
            Root = root;
            Context = context;
            this.owner = owner;
        }

        internal static TemporaryPayloadSession Create(SupportProfile profile)
        {
            if (profile == null) throw new ArgumentException("Unknown dependency profile.");
            string parent = SessionParent();
            Directory.CreateDirectory(parent);
            PruneAbandoned(parent);
            string root = Path.Combine(parent, Guid.NewGuid().ToString("N"));
            Directory.CreateDirectory(root);
            SessionOwner owner;
            using (Process launcher = Process.GetCurrentProcess())
                owner = new SessionOwner(Path.GetFileName(root), launcher.Id,
                    launcher.StartTime.ToUniversalTime().Ticks, 0, 0);
            WriteOwner(root, owner);
            try
            {
                var ui = DependencyRuntime.Metadata(DependencyRuntime.UiMetadataResource);
                var ysm = DependencyRuntime.Metadata(DependencyRuntime.YsmMetadataResource);
                WriteVerified(root, "ui/tempos-ui-runtime.jar",
                    DependencyRuntime.ReadResourceBytes("Tempos.UiRuntime.jar"), RequiredHash(ui, "sha256"));
                string[] selected = DependencyRuntime.RequiredYsm(profile);
                var found = new HashSet<string>(StringComparer.Ordinal);
                using (var memory = new MemoryStream(DependencyRuntime.ReadResourceBytes("Tempos.Ysm.zip"), false))
                using (var archive = new ZipArchive(memory, ZipArchiveMode.Read))
                {
                    foreach (ZipArchiveEntry entry in archive.Entries)
                    {
                        if (ZipEntries.IsDirectory(entry)) continue;
                        if (!ysm.ContainsKey(entry.FullName) || !found.Add(entry.FullName))
                            throw new InvalidDataException("Invalid embedded YSM entry: " + entry.FullName);
                        byte[] bytes = ZipEntries.ReadBytes(entry);
                        string expected = RequiredHash(ysm, entry.FullName);
                        if (Hashing.Sha256(bytes) != expected)
                            throw new InvalidDataException("Embedded YSM SHA-256 mismatch: " + entry.FullName);
                        if (Array.IndexOf(selected, entry.FullName) >= 0)
                            WriteVerified(root, entry.FullName, bytes, expected);
                    }
                }
                if (found.Count != ysm.Count)
                    throw new InvalidDataException("The embedded YSM package is incomplete.");
                foreach (string name in selected)
                    if (!found.Contains(name)) throw new InvalidDataException("Missing embedded dependency: " + name);

                var content = new StringBuilder("format=4\nprofile=" + profile.GameId
                    + "\njava.minimum=" + profile.JavaVersion + "\nroot=.\nui=ui/tempos-ui-runtime.jar\n");
                content.Append("ui.sha256=").Append(RequiredHash(ui, "sha256")).Append('\n');
                content.Append("module=").Append(profile.YsmModuleName).Append('\n');
                foreach (string name in selected)
                    content.Append(name).Append(".sha256=").Append(RequiredHash(ysm, name)).Append('\n');
                byte[] contextBytes = Encoding.UTF8.GetBytes(content.ToString());
                string contextPath = Path.Combine(root, "contexts", profile.ProfileId + ".properties");
                Directory.CreateDirectory(Path.GetDirectoryName(contextPath));
                File.WriteAllBytes(contextPath, contextBytes);
                return new TemporaryPayloadSession(root,
                    new DependencyRuntime.Context(contextPath, Hashing.Sha256(contextBytes)), owner);
            }
            catch
            {
                TryDeleteOwned(root, owner);
                throw;
            }
        }

        // The helper survives the loader window. Lazy JVM class loading keeps its files until game exit.
        internal long RetainForProcess(int processId)
        {
            if (retained) return retainedStarted;
            long started;
            using (Process target = Process.GetProcessById(processId))
                started = target.StartTime.ToUniversalTime().Ticks;
            var gameOwner = new SessionOwner(owner.Id, owner.LauncherPid, owner.LauncherStarted,
                processId, started);
            WriteOwner(Root, gameOwner);
            retainedStarted = started;
            retained = true;
            PortableConfig.StartFinalizer(Root, Assembly.GetExecutingAssembly().Location, processId, started);
            return retainedStarted;
        }

        public void Dispose()
        {
            if (!retained) TryDeleteOwned(Root, owner);
        }

        private static string RequiredHash(IDictionary<string, string> metadata, string key)
        {
            string hash;
            if (!metadata.TryGetValue(key, out hash) || !Regex.IsMatch(hash, "^[0-9a-f]{64}$"))
                throw new InvalidDataException("Invalid embedded dependency SHA-256: " + key);
            return hash;
        }

        private static void WriteVerified(string root, string relative, byte[] bytes, string expected)
        {
            if (!Regex.IsMatch(relative, "^(?:ui/tempos-ui-runtime|libraries/tempos-ysm-(?:core|codecs|images)|modules/tempos-ysm-[A-Za-z0-9._-]+)\\.jar$"))
                throw new InvalidDataException("Invalid temporary dependency path: " + relative);
            if (Hashing.Sha256(bytes) != expected)
                throw new InvalidDataException("Embedded dependency SHA-256 mismatch: " + relative);
            string target = Path.Combine(root, relative.Replace('/', Path.DirectorySeparatorChar));
            Directory.CreateDirectory(Path.GetDirectoryName(target));
            File.WriteAllBytes(target, bytes);
            if (Hashing.Sha256(target) != expected)
                throw new InvalidDataException("Temporary dependency SHA-256 mismatch: " + relative);
        }

        private static string SessionParent()
        {
            return Path.GetFullPath(Path.Combine(Path.GetTempPath(), "tempos"));
        }

        private static void PruneAbandoned(string parent)
        {
            foreach (string root in Directory.GetDirectories(parent))
            {
                try
                {
                    if (File.Exists(Path.Combine(root, ".tempos-portable-session.json"))) continue;
                    SessionOwner owner = ReadOwner(root);
                    if (owner == null) continue;
                    using (Process launcher = MatchingProcess(owner.LauncherPid, owner.LauncherStarted))
                        if (launcher != null) continue;
                    using (Process game = MatchingProcess(owner.GamePid, owner.GameStarted))
                        if (game != null) continue;
                    TryDeleteOwned(root, owner);
                }
                catch (IOException) { }
                catch (UnauthorizedAccessException) { }
                catch (System.ComponentModel.Win32Exception) { }
            }
        }

        private static Process MatchingProcess(int pid, long started)
        {
            if (pid <= 0 || started <= 0) return null;
            Process process;
            try { process = Process.GetProcessById(pid); }
            catch (ArgumentException) { return null; }
            try
            {
                if (process.StartTime.ToUniversalTime().Ticks == started) return process;
            }
            catch (InvalidOperationException) { }
            catch { process.Dispose(); throw; }
            process.Dispose();
            return null;
        }

        private static void WriteOwner(string root, SessionOwner owner)
        {
            File.WriteAllText(Path.Combine(root, MarkerName), "format=1\nid=" + owner.Id
                + "\nlauncher.pid=" + owner.LauncherPid.ToString(CultureInfo.InvariantCulture)
                + "\nlauncher.started=" + owner.LauncherStarted.ToString(CultureInfo.InvariantCulture)
                + "\ngame.pid=" + owner.GamePid.ToString(CultureInfo.InvariantCulture)
                + "\ngame.started=" + owner.GameStarted.ToString(CultureInfo.InvariantCulture) + "\n", new UTF8Encoding(false));
        }

        private static SessionOwner ReadOwner(string root)
        {
            root = Path.GetFullPath(root).TrimEnd(Path.DirectorySeparatorChar);
            string id = Path.GetFileName(root);
            if (!Regex.IsMatch(id, "^[0-9a-f]{32}$")
                || !String.Equals(Path.GetDirectoryName(root), SessionParent(), StringComparison.OrdinalIgnoreCase)
                || !Directory.Exists(root) || IsLink(root)) return null;
            string marker = Path.Combine(root, MarkerName);
            if (!File.Exists(marker) || IsLink(marker)) return null;
            var values = RuntimeMetadata.Parse(File.ReadAllText(marker));
            int launcherPid, gamePid;
            long launcherStarted, gameStarted;
            string value;
            if (!values.TryGetValue("format", out value) || value != "1"
                || !values.TryGetValue("id", out value) || value != id
                || !values.TryGetValue("launcher.pid", out value) || !Int32.TryParse(value, out launcherPid) || launcherPid <= 0
                || !values.TryGetValue("launcher.started", out value) || !Int64.TryParse(value, out launcherStarted) || launcherStarted <= 0
                || !values.TryGetValue("game.pid", out value) || !Int32.TryParse(value, out gamePid) || gamePid < 0
                || !values.TryGetValue("game.started", out value) || !Int64.TryParse(value, out gameStarted) || gameStarted < 0
                || (gamePid == 0) != (gameStarted == 0)) return null;
            return new SessionOwner(id, launcherPid, launcherStarted, gamePid, gameStarted);
        }

        private static bool TryDeleteOwned(string root, SessionOwner expected)
        {
            try
            {
                if (!Directory.Exists(root)) return true;
                SessionOwner actual = ReadOwner(root);
                if (actual == null || !actual.Equals(expected)) return false;
                // Never traverse a junction introduced into the owned directory.
                var pending = new Stack<string>();
                pending.Push(root);
                while (pending.Count != 0)
                {
                    foreach (string entry in Directory.GetFileSystemEntries(pending.Pop()))
                    {
                        if (IsLink(entry)) return false;
                        if (Directory.Exists(entry)) pending.Push(entry);
                    }
                }
                Directory.Delete(root, true);
                return true;
            }
            catch (IOException) { return false; }
            catch (UnauthorizedAccessException) { return false; }
        }

        private static bool IsLink(string path)
        {
            return (File.GetAttributes(path) & FileAttributes.ReparsePoint) != 0;
        }

        private sealed class SessionOwner
        {
            internal readonly string Id;
            internal readonly int LauncherPid, GamePid;
            internal readonly long LauncherStarted, GameStarted;
            internal SessionOwner(string id, int launcherPid, long launcherStarted, int gamePid, long gameStarted)
            {
                Id = id; LauncherPid = launcherPid; LauncherStarted = launcherStarted;
                GamePid = gamePid; GameStarted = gameStarted;
            }
            public override bool Equals(object other)
            {
                var owner = other as SessionOwner;
                return owner != null && Id == owner.Id && LauncherPid == owner.LauncherPid
                    && LauncherStarted == owner.LauncherStarted && GamePid == owner.GamePid && GameStarted == owner.GameStarted;
            }
            public override int GetHashCode() { return Id.GetHashCode(); }
        }
    }
}
