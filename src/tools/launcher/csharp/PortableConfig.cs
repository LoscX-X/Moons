using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.Reflection;
using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;
using System.Web.Script.Serialization;

namespace Tempos.WindowsLauncher
{
    /// <summary>Parameter-only JSON stored after the unchanged PE image.</summary>
    internal static class PortableConfig
    {
        internal const string ManifestName = ".tempos-portable-session.json";
        internal const int MaxPayload = 64 * 1024 * 1024;
        private static readonly byte[] Header = Encoding.ASCII.GetBytes("TEMPOSCFG-BEGIN1");
        private static readonly byte[] Footer = Encoding.ASCII.GetBytes("TEMPOSCFG-END-v1");
        private const int HeaderSize = 60, FooterSize = 24;
        private static readonly UTF8Encoding Utf8 = new UTF8Encoding(false, true);

        internal sealed class Prepared
        {
            internal readonly string Home, ProfilePath, Selected;
            internal readonly LegacyConfigMigration.Candidate Legacy;
            internal Prepared(string home, string profile, string selected, LegacyConfigMigration.Candidate legacy)
            { Home = home; ProfilePath = profile; Selected = selected; Legacy = legacy; }
        }

        internal static JavaScriptSerializer Json()
        { return new JavaScriptSerializer { MaxJsonLength = MaxPayload, RecursionLimit = 128 }; }

        internal static Dictionary<string, object> Object(object value, string label)
        {
            var result = value as Dictionary<string, object>;
            if (result == null) throw new InvalidDataException("Expected " + label + ".");
            return result;
        }

        internal static bool ValidName(string name)
        {
            if (name != null) name = name.Trim();
            return name != null
                && Regex.IsMatch(name, @"^[\p{L}\p{N}_-][\p{L}\p{N} _-]{0,47}$")
                && !Regex.IsMatch(name, "^(CON|PRN|AUX|NUL|COM[0-9]|LPT[0-9])$", RegexOptions.IgnoreCase);
        }

        internal static string CanonicalName(string name)
        {
            if (!ValidName(name)) throw new InvalidDataException("Invalid profile name.");
            name = name.Trim();
            return name.Equals("default", StringComparison.OrdinalIgnoreCase) ? "default" : name;
        }

        internal static Dictionary<string, object> Profile(object document)
        {
            var profile = Object(document, "a parameter profile");
            object format, raw;
            if (!profile.TryGetValue("format", out format)) throw new InvalidDataException("Missing profile format.");
            if (Convert.ToString(format, CultureInfo.InvariantCulture) == "1") {
                if (!profile.TryGetValue("modules", out raw)) throw new InvalidDataException("Missing legacy modules.");
                Object(raw, "legacy module states");
                if (!profile.TryGetValue("bindings", out raw)) throw new InvalidDataException("Missing legacy bindings.");
                Object(raw, "legacy bindings");
                if (Encode(profile).Length > 2000000) throw new InvalidDataException("Parameter profile is too large.");
                return profile;
            }
            if (Convert.ToString(format, CultureInfo.InvariantCulture) != "2" || !profile.TryGetValue("values", out raw))
                throw new InvalidDataException("Expected a parameter profile.");
            foreach (var pair in Object(raw, "parameter values")) {
                object value = pair.Value;
                if (!(value is string || value is bool || value is int || value is long || value is decimal || value is double))
                    throw new InvalidDataException("Expected a scalar parameter: " + pair.Key);
                if (value is double && (Double.IsNaN((double)value) || Double.IsInfinity((double)value)))
                    throw new InvalidDataException("Non-finite parameter: " + pair.Key);
            }
            if (profile.TryGetValue("modules", out raw)) Object(raw, "module states");
            if (Encode(profile).Length > 2000000) throw new InvalidDataException("Parameter profile is too large.");
            return profile;
        }

        internal static Dictionary<string, object> Bundle(byte[] bytes)
        {
            if (bytes == null || bytes.Length == 0 || bytes.Length > MaxPayload)
                throw new InvalidDataException("Invalid parameter cache size.");
            var document = Object(Json().DeserializeObject(Utf8.GetString(bytes).TrimStart('\ufeff')), "parameter JSON");
            object raw;
            if (document.TryGetValue("values", out raw) || document.ContainsKey("bindings")) {
                var profiles = new Dictionary<string, object>(StringComparer.OrdinalIgnoreCase) { { "default", Profile(document) } };
                return NewBundle("default", profiles);
            }
            object format, selected;
            if (!document.TryGetValue("format", out format) || Convert.ToString(format, CultureInfo.InvariantCulture) != "1"
                || !document.TryGetValue("selected", out selected) || !(selected is string)
                || !document.TryGetValue("profiles", out raw)) throw new InvalidDataException("Invalid parameter cache format.");
            var incoming = Object(raw, "named profiles");
            var all = new Dictionary<string, object>(StringComparer.Ordinal);
            var names = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
            foreach (var pair in incoming) {
                string name = CanonicalName(pair.Key);
                if (!names.Add(name)) throw new InvalidDataException("Duplicate profile name.");
                Profile(pair.Value);
                all.Add(name, pair.Value);
            }
            selected = CanonicalName((string)selected);
            if (!all.ContainsKey((string)selected) || !all.ContainsKey("default"))
                throw new InvalidDataException("Missing selected or default profile.");
            Dictionary<string, object> extras = document.TryGetValue("extras", out raw) ? Object(raw, "model parameter texts") : new Dictionary<string, object>();
            ValidateExtras(extras);
            return NewBundle((string)selected, all, extras);
        }

        internal static Dictionary<string, object> NewBundle(string selected, Dictionary<string, object> profiles,
            Dictionary<string, object> extras = null)
        { return new Dictionary<string, object> { { "format", 1 }, { "selected", selected }, { "profiles", profiles }, { "extras", extras ?? new Dictionary<string, object>() } }; }

        internal static bool ValidExtra(string relative)
        {
            return relative == "data/ysm/ysm.properties"
                || Regex.IsMatch(relative ?? "", "^config/legacy/(moons|tempos)-(moons|tempos)\\.properties$")
                || Regex.IsMatch(relative ?? "", "^data/ysm/parameters/[A-Za-z0-9_-]+(?:\\.[A-Za-z0-9_-]+)*\\.json$");
        }

        internal static Encoding ExtraEncoding(string relative)
        {
            return relative.StartsWith("config/legacy/", StringComparison.Ordinal)
                ? Encoding.GetEncoding(28591, EncoderFallback.ExceptionFallback, DecoderFallback.ExceptionFallback) : Utf8;
        }

        private static void ValidateExtras(Dictionary<string, object> extras)
        {
            foreach (var pair in extras) {
                string text = pair.Value as string;
                if (!ValidExtra(pair.Key) || text == null || text.IndexOf('\0') >= 0 || Utf8.GetByteCount(text) > 2000000)
                    throw new InvalidDataException("Invalid model parameter text.");
                ExtraEncoding(pair.Key).GetByteCount(text);
                if (pair.Key.EndsWith(".json", StringComparison.Ordinal)) Json().DeserializeObject(text);
            }
        }

        internal static byte[] Encode(object document)
        { return Utf8.GetBytes(Json().Serialize(document)); }

        internal static string Digest(byte[] bytes)
        {
            using (var sha = SHA256.Create()) return BitConverter.ToString(sha.ComputeHash(bytes)).Replace("-", "").ToLowerInvariant();
        }

        internal static string FileDigest(string file)
        {
            using (var stream = File.OpenRead(file))
            using (var sha = SHA256.Create()) return BitConverter.ToString(sha.ComputeHash(stream)).Replace("-", "").ToLowerInvariant();
        }

        private sealed class Overlay
        { internal long BaseLength; internal byte[] Payload; }

        internal static byte[] Read(string executable)
        {
            using (var stream = new FileStream(executable, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete)) {
                Overlay overlay = ReadOverlay(stream);
                return overlay == null ? null : overlay.Payload;
            }
        }

        private static Overlay ReadOverlay(FileStream stream)
        {
            long length = stream.Length;
            if (length < 64) throw new InvalidDataException("Executable is truncated.");
            using (var reader = new BinaryReader(stream, Encoding.UTF8, true)) {
                stream.Position = 0;
                if (reader.ReadUInt16() != 0x5a4d) throw new InvalidDataException("Expected a Windows executable.");
                stream.Position = 0x3c;
                long pe = reader.ReadUInt32();
                if (pe < 64 || pe > length - 24) throw new InvalidDataException("Invalid executable header.");
                stream.Position = pe;
                if (reader.ReadUInt32() != 0x00004550) throw new InvalidDataException("Invalid executable signature.");
                stream.Position = length - FooterSize;
                if (!Equal(reader.ReadBytes(Footer.Length), Footer)) {
                    // A recognized header without its footer is a torn cache, never a clean EXE.
                    int count = (int)Math.Min(length, MaxPayload + HeaderSize + FooterSize);
                    stream.Position = length - count;
                    byte[] tail = reader.ReadBytes(count);
                    for (int i = 0; i <= tail.Length - HeaderSize; i++) {
                        if (EqualAt(tail, i, Header) && BitConverter.ToInt32(tail, i + 16) == 1) {
                            long advertised = BitConverter.ToInt64(tail, i + 20);
                            if (advertised > 0 && advertised <= MaxPayload)
                                throw new InvalidDataException("Parameter cache is truncated or has a damaged footer.");
                        }
                    }
                    return null;
                }
                long baseLength = reader.ReadInt64();
                if (baseLength < pe + 24 || baseLength > length - HeaderSize - FooterSize)
                    throw new InvalidDataException("Invalid parameter cache boundary.");
                stream.Position = baseLength;
                if (!Equal(reader.ReadBytes(Header.Length), Header) || reader.ReadInt32() != 1)
                    throw new InvalidDataException("Invalid parameter cache header.");
                long payloadLength = reader.ReadInt64();
                byte[] hash = reader.ReadBytes(32);
                if (payloadLength < 1 || payloadLength > MaxPayload
                    || baseLength + HeaderSize + payloadLength + FooterSize != length)
                    throw new InvalidDataException("Invalid parameter cache length.");
                byte[] payload = reader.ReadBytes((int)payloadLength);
                using (var sha = SHA256.Create())
                    if (!Equal(hash, sha.ComputeHash(payload))) throw new InvalidDataException("Parameter cache SHA-256 mismatch.");
                Bundle(payload);
                return new Overlay { BaseLength = baseLength, Payload = payload };
            }
        }

        private static bool Equal(byte[] first, byte[] second)
        { return first.Length == second.Length && EqualAt(first, 0, second); }
        private static bool EqualAt(byte[] bytes, int start, byte[] expected)
        {
            if (start < 0 || start > bytes.Length - expected.Length) return false;
            for (int i = 0; i < expected.Length; i++) if (bytes[start + i] != expected[i]) return false;
            return true;
        }

        internal static string Write(string executable, byte[] parameters)
        {
            byte[] payload = Encode(Bundle(parameters));
            executable = Path.GetFullPath(executable);
            if ((File.GetAttributes(executable) & FileAttributes.ReparsePoint) != 0)
                throw new IOException("Cannot replace a linked executable.");
            string temporary = executable + ".params-" + Guid.NewGuid().ToString("N");
            try {
                using (var source = new FileStream(executable, FileMode.Open, FileAccess.Read, FileShare.Read)) {
                    Overlay previous = ReadOverlay(source);
                    long remaining = previous == null ? source.Length : previous.BaseLength;
                    long baseLength = remaining;
                    source.Position = 0;
                    using (var target = new FileStream(temporary, FileMode.CreateNew, FileAccess.Write, FileShare.None))
                    using (var writer = new BinaryWriter(target, Encoding.UTF8, true)) {
                        byte[] buffer = new byte[65536];
                        while (remaining != 0) {
                            int read = source.Read(buffer, 0, (int)Math.Min(buffer.Length, remaining));
                            if (read == 0) throw new EndOfStreamException();
                            target.Write(buffer, 0, read); remaining -= read;
                        }
                        writer.Write(Header); writer.Write(1); writer.Write((long)payload.Length);
                        using (var sha = SHA256.Create()) writer.Write(sha.ComputeHash(payload));
                        writer.Write(payload); writer.Write(Footer); writer.Write(baseLength);
                        writer.Flush(); target.Flush(true);
                    }
                }
                if (!Equal(Read(temporary), payload)) throw new IOException("Staged parameter cache verification failed.");
                File.Replace(temporary, executable, null);
                if (!Equal(Read(executable), payload)) throw new IOException("Parameter cache verification failed.");
                return Digest(payload);
            } finally { if (File.Exists(temporary)) File.Delete(temporary); }
        }

        internal static Prepared Prepare(string root, string executable, string importedPath)
        {
            root = SessionRoot(root);
            string home = Path.Combine(root, "home"), profilesDirectory = Path.Combine(home, "config", "profiles");
            Directory.CreateDirectory(profilesDirectory);
            LegacyConfigMigration.Candidate legacy = null;
            byte[] initial = importedPath == null ? Read(executable) : File.ReadAllBytes(importedPath);
            object recoveredLegacy = null;
            var recoveries = new List<object>();
            if (importedPath == null) RecoverParameters(root, executable, ref initial, ref recoveredLegacy, recoveries);
            if (initial == null) { legacy = LegacyConfigMigration.Capture(); if (legacy != null) initial = legacy.Bundle; }
            string selected = "default";
            if (initial != null) {
                var bundle = Bundle(initial);
                selected = (string)bundle["selected"];
                foreach (var pair in Object(bundle["profiles"], "profiles"))
                    File.WriteAllBytes(Path.Combine(profilesDirectory, pair.Key + ".json"), Encode(pair.Value));
                foreach (var pair in Object(bundle["extras"], "model parameter texts")) {
                    string target = Path.Combine(home, pair.Key.Replace('/', Path.DirectorySeparatorChar));
                    Directory.CreateDirectory(Path.GetDirectoryName(target));
                    File.WriteAllText(target, (string)pair.Value, ExtraEncoding(pair.Key));
                }
            }
            if (legacy != null) {
                if (initial != null) legacy.RestoreProfileTimes(profilesDirectory);
                else legacy.CopyExtras(home);
                legacy.CopyLegacyProperties(home);
            }
            File.WriteAllText(Path.Combine(home, "config", "selected-profile.info"), selected + "\n", Utf8);
            ExtractDialogHelper(root);
            using (var launcher = Process.GetCurrentProcess()) {
                var manifest = new Dictionary<string, object> {
                    { "format", 1 }, { "root", root }, { "executable", Path.GetFullPath(executable) },
                    { "launcherPid", launcher.Id }, { "launcherStarted", launcher.StartTime.ToUniversalTime().Ticks.ToString(CultureInfo.InvariantCulture) },
                    { "gamePid", 0 }, { "gameStarted", "0" }, { "legacy", legacy == null ? recoveredLegacy : legacy.Metadata() },
                    { "recoveries", recoveries.ToArray() }
                };
                WriteManifest(root, manifest);
            }
            return new Prepared(home, Path.Combine(profilesDirectory, selected + ".json"), selected, legacy);
        }

        private static void ExtractDialogHelper(string root)
        {
            using (var resource = Assembly.GetExecutingAssembly().GetManifestResourceStream("Tempos.Finalizer.exe")) {
                if (resource == null) return; // Independent parameter-only probes have no embedded helper.
                using (var file = new FileStream(Path.Combine(root, "tempos-dialog.exe"), FileMode.CreateNew, FileAccess.Write, FileShare.None))
                    resource.CopyTo(file);
            }
        }

        private static void RecoverParameters(string currentRoot, string executable, ref byte[] initial,
            ref object legacy, List<object> recoveries)
        {
            string parent = Path.GetDirectoryName(currentRoot);
            DateTime newest = initial == null ? DateTime.MinValue : File.GetLastWriteTimeUtc(executable);
            foreach (string directory in Directory.GetDirectories(parent)) {
                if (String.Equals(directory, currentRoot, StringComparison.OrdinalIgnoreCase)
                    || !File.Exists(Path.Combine(directory, ManifestName))) continue;
                try {
                    var manifest = Manifest(directory);
                    if (!String.Equals(Path.GetFullPath(executable), (string)manifest["executable"], StringComparison.OrdinalIgnoreCase)
                        || OwnerAlive(manifest)) continue;
                    string lockFile = Path.Combine(directory, ".tempos-parameter-commit.lock");
                    if (File.Exists(lockFile) && IsLink(lockFile)) continue;
                    using (var reservation = new FileStream(lockFile, FileMode.OpenOrCreate, FileAccess.ReadWrite, FileShare.None)) {
                        byte[] bundle = Collect(directory);
                        recoveries.Add(new Dictionary<string, object> { { "root", directory }, { "hash", Digest(bundle) } });
                        DateTime changed = SavedParametersChanged(directory);
                        if (manifest.ContainsKey("committed") || changed <= newest) continue;
                        initial = bundle; newest = changed; legacy = manifest["legacy"];
                    }
                } catch (IOException) { }
                catch (UnauthorizedAccessException) { }
                catch (System.ComponentModel.Win32Exception) { }
                catch (ArgumentException) { }
                catch (KeyNotFoundException) { }
                catch (InvalidCastException) { }
                catch (FormatException) { }
                catch (OverflowException) { }
            }
        }

        private static DateTime SavedParametersChanged(string root)
        {
            DateTime latest = DateTime.MinValue;
            string home = Path.Combine(root, "home"), profiles = Path.Combine(home, "config", "profiles");
            foreach (string file in Directory.GetFiles(profiles, "*.json"))
                if (File.GetLastWriteTimeUtc(file) > latest) latest = File.GetLastWriteTimeUtc(file);
            string modelParameters = Path.Combine(home, "data", "ysm", "parameters");
            if (Directory.Exists(modelParameters))
                foreach (string file in Directory.GetFiles(modelParameters, "*.json"))
                    if (File.GetLastWriteTimeUtc(file) > latest) latest = File.GetLastWriteTimeUtc(file);
            foreach (string file in new[] { Path.Combine(home, "config", "selected-profile.info"), Path.Combine(home, "data", "ysm", "ysm.properties") })
                if (File.Exists(file) && File.GetLastWriteTimeUtc(file) > latest) latest = File.GetLastWriteTimeUtc(file);
            return latest;
        }

        private static bool OwnerAlive(Dictionary<string, object> manifest)
        {
            return Alive(Convert.ToInt32(manifest["launcherPid"], CultureInfo.InvariantCulture), Int64.Parse((string)manifest["launcherStarted"], CultureInfo.InvariantCulture))
                || Alive(Convert.ToInt32(manifest["gamePid"], CultureInfo.InvariantCulture), Int64.Parse((string)manifest["gameStarted"], CultureInfo.InvariantCulture));
        }

        private static bool Alive(int pid, long started)
        {
            if (pid <= 0 || started <= 0) return false;
            Process process;
            try { process = Process.GetProcessById(pid); } catch (ArgumentException) { return false; }
            using (process) {
                try { return process.StartTime.ToUniversalTime().Ticks == started; }
                catch (InvalidOperationException) { return false; }
            }
        }

        internal static void CompleteRecoveries(Dictionary<string, object> manifest)
        {
            object raw;
            if (!manifest.TryGetValue("recoveries", out raw)) return;
            var records = raw as object[];
            if (records == null) throw new InvalidDataException("Invalid parameter recovery records.");
            foreach (object value in records) {
                try {
                    var record = Object(value, "parameter recovery");
                    if (!Directory.Exists((string)record["root"])) continue;
                    string root = SessionRoot((string)record["root"]);
                    if (String.Equals(root, (string)manifest["root"], StringComparison.OrdinalIgnoreCase)) continue;
                    var old = Manifest(root);
                    if (OwnerAlive(old) || !String.Equals((string)old["executable"], (string)manifest["executable"], StringComparison.OrdinalIgnoreCase)) continue;
                    bool verified;
                    using (var reservation = new FileStream(Path.Combine(root, ".tempos-parameter-commit.lock"), FileMode.OpenOrCreate, FileAccess.ReadWrite, FileShare.None))
                        verified = Digest(Collect(root)) == (string)record["hash"];
                    if (verified) DeleteSession(root);
                } catch (IOException) { }
                catch (UnauthorizedAccessException) { }
            }
        }

        internal static string SessionRoot(string root)
        {
            root = Path.GetFullPath(root).TrimEnd(Path.DirectorySeparatorChar);
            string parent = Path.GetFullPath(Path.Combine(Path.GetTempPath(), "tempos")).TrimEnd(Path.DirectorySeparatorChar);
            if (!Regex.IsMatch(Path.GetFileName(root), "^[0-9a-f]{32}$")
                || !String.Equals(Path.GetDirectoryName(root), parent, StringComparison.OrdinalIgnoreCase)
                || !Directory.Exists(root) || IsLink(root)
                || !File.Exists(Path.Combine(root, ".tempos-session")))
                throw new IOException("Invalid temporary parameter session.");
            return root;
        }

        internal static bool IsLink(string path)
        { return (File.GetAttributes(path) & FileAttributes.ReparsePoint) != 0; }

        internal static Dictionary<string, object> Manifest(string root)
        {
            root = SessionRoot(root);
            string path = Path.Combine(root, ManifestName);
            if (IsLink(path)) throw new IOException("Linked parameter manifest.");
            var value = Object(Json().DeserializeObject(File.ReadAllText(path, Utf8)), "a parameter session");
            if (Convert.ToString(value["format"], CultureInfo.InvariantCulture) != "1"
                || !String.Equals((string)value["root"], root, StringComparison.OrdinalIgnoreCase))
                throw new InvalidDataException("Parameter session identity mismatch.");
            return value;
        }

        internal static void WriteManifest(string root, Dictionary<string, object> manifest)
        {
            root = SessionRoot(root);
            string path = Path.Combine(root, ManifestName), temporary = path + ".tmp-" + Guid.NewGuid().ToString("N");
            try {
                File.WriteAllBytes(temporary, Encode(manifest));
                if (File.Exists(path)) File.Replace(temporary, path, null);
                else File.Move(temporary, path);
            } finally { if (File.Exists(temporary)) File.Delete(temporary); }
        }

        internal static byte[] Collect(string root)
        {
            root = SessionRoot(root);
            string directory = Path.Combine(root, "home", "config", "profiles");
            if (!Directory.Exists(directory)) throw new IOException("The game did not save parameter profiles.");
            var profiles = new Dictionary<string, object>(StringComparer.OrdinalIgnoreCase);
            foreach (string file in Directory.GetFiles(directory, "*.json")) {
                string name = CanonicalName(Path.GetFileNameWithoutExtension(file));
                if (IsLink(file)) throw new IOException("Invalid saved profile.");
                if (new FileInfo(file).Length > 2000000) throw new IOException("Saved profile is too large.");
                profiles.Add(name, Profile(Json().DeserializeObject(File.ReadAllText(file, Utf8))));
            }
            string selectedFile = Path.Combine(root, "home", "config", "selected-profile.info");
            string selected = File.Exists(selectedFile) ? File.ReadAllText(selectedFile, Utf8).Trim() : "default";
            if (selected.EndsWith(".json", StringComparison.OrdinalIgnoreCase)) selected = selected.Substring(0, selected.Length - 5);
            var extras = new Dictionary<string, object>();
            string ysm = Path.Combine(root, "home", "data", "ysm"), properties = Path.Combine(ysm, "ysm.properties");
            if (File.Exists(properties)) ReadExtra(extras, "data/ysm/ysm.properties", properties);
            string parameters = Path.Combine(ysm, "parameters");
            if (Directory.Exists(parameters)) {
                if (IsLink(parameters)) throw new IOException("Linked model parameter folder.");
                foreach (string file in Directory.GetFiles(parameters, "*.json"))
                    ReadExtra(extras, "data/ysm/parameters/" + Path.GetFileName(file), file);
            }
            string legacy = Path.Combine(root, "home", "config", "legacy");
            if (Directory.Exists(legacy)) {
                if (IsLink(legacy)) throw new IOException("Linked legacy parameter folder.");
                foreach (string file in Directory.GetFiles(legacy, "*.properties"))
                    ReadExtra(extras, "config/legacy/" + Path.GetFileName(file), file);
            }
            byte[] bundle = Encode(NewBundle(selected, profiles, extras));
            Bundle(bundle);
            return bundle;
        }

        private static void ReadExtra(Dictionary<string, object> extras, string relative, string file)
        {
            if (!ValidExtra(relative) || IsLink(file) || new FileInfo(file).Length > 2000000)
                throw new IOException("Invalid model parameter file.");
            extras.Add(relative, relative.StartsWith("config/legacy/", StringComparison.Ordinal)
                ? ExtraEncoding(relative).GetString(File.ReadAllBytes(file)) : File.ReadAllText(file, Utf8));
        }

        internal static void StartFinalizer(string root, string executable, int gamePid, long gameStarted)
        {
            var manifest = Manifest(root);
            if (gamePid <= 0 || gameStarted <= 0
                || !String.Equals(Path.GetFullPath(executable), (string)manifest["executable"], StringComparison.OrdinalIgnoreCase))
                throw new InvalidDataException("Invalid parameter finalizer target.");
            manifest["gamePid"] = gamePid;
            manifest["gameStarted"] = gameStarted.ToString(CultureInfo.InvariantCulture);
            WriteManifest(root, manifest);
            string helperId = Guid.NewGuid().ToString("N");
            string helperRoot = Path.Combine(Path.GetTempPath(), "tempos-finalizers", helperId);
            Directory.CreateDirectory(helperRoot);
            string helper = Path.Combine(helperRoot, "finalize.exe");
            try {
                using (var resource = Assembly.GetExecutingAssembly().GetManifestResourceStream("Tempos.Finalizer.exe")) {
                    if (resource == null) throw new IOException("Missing embedded parameter finalizer.");
                    using (var file = new FileStream(helper, FileMode.CreateNew, FileAccess.Write, FileShare.None)) resource.CopyTo(file);
                }
                File.WriteAllText(Path.Combine(helperRoot, ".tempos-finalizer"), helperId, Utf8);
                manifest["helperRoot"] = helperRoot;
                WriteManifest(root, manifest);
                var start = new ProcessStartInfo(helper, "--session " + Quote(root)) {
                    UseShellExecute = false, CreateNoWindow = true, WindowStyle = ProcessWindowStyle.Hidden
                };
                using (var process = Process.Start(start)) {
                    if (process == null) throw new IOException("Could not start the parameter finalizer.");
                }
            } catch {
                if (File.Exists(helper)) File.Delete(helper);
                if (Directory.Exists(helperRoot)) Directory.Delete(helperRoot, true);
                throw;
            }
        }

        internal static string Quote(string argument)
        {
            if (argument == null || argument.IndexOf('"') >= 0 || argument.IndexOf('\0') >= 0)
                throw new ArgumentException("Invalid process argument.");
            return "\"" + argument + "\"";
        }

        internal static void DeleteSession(string root)
        {
            root = SessionRoot(root);
            var pending = new Stack<string>(); pending.Push(root);
            while (pending.Count != 0) {
                foreach (string path in Directory.GetFileSystemEntries(pending.Pop())) {
                    if (IsLink(path)) throw new IOException("A linked file prevents session cleanup.");
                    if (Directory.Exists(path)) pending.Push(path);
                }
            }
            Directory.Delete(root, true);
        }
    }
}
