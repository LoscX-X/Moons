using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Text;

namespace Tempos.WindowsLauncher
{
    /// <summary>The old names here identify migration sources, never new storage locations.</summary>
    internal static class LegacyConfigMigration
    {
        internal sealed class Source
        {
            internal string Root;
            internal bool SafeToDelete = true;
            internal readonly Dictionary<string, object> Files = new Dictionary<string, object>(StringComparer.OrdinalIgnoreCase);
            internal readonly Dictionary<string, object> Links = new Dictionary<string, object>(StringComparer.OrdinalIgnoreCase);
            internal readonly HashSet<string> ParameterFiles = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
            internal readonly Dictionary<string, string> CapturedFiles = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
            internal object Metadata()
            { return new Dictionary<string, object> { { "root", Root }, { "safe", SafeToDelete }, { "files", Files }, { "links", Links } }; }
        }

        internal sealed class Candidate
        {
            internal byte[] Bundle;
            internal string Properties;
            internal LegacyModelMigration.Result Models;
            internal readonly Dictionary<string, long> ProfileTimes = new Dictionary<string, long>(StringComparer.OrdinalIgnoreCase);
            internal readonly Dictionary<string, object> Extras = new Dictionary<string, object>();
            internal readonly List<Source> Sources = new List<Source>();
            internal readonly List<string> ModelDirectories = new List<string>();
            internal object Metadata()
            {
                var sources = new List<object>();
                foreach (var source in Sources) sources.Add(source.Metadata());
                return new Dictionary<string, object> { { "sources", sources.ToArray() }, { "models", Models == null ? null : Models.Metadata() } };
            }
            internal void CopyLegacyProperties(string home)
            {
                if (Properties == null) return;
                string target = Path.Combine(home, "config", "tempos.properties");
                byte[] bytes = File.ReadAllBytes(Properties);
                if (Models != null) {
                    var encoding = Encoding.GetEncoding(28591);
                    string origin = Path.GetDirectoryName(Path.GetDirectoryName(Properties));
                    bytes = encoding.GetBytes(Models.RewriteText(encoding.GetString(bytes), origin));
                }
                File.WriteAllBytes(target, bytes);
                File.SetLastWriteTimeUtc(target, File.GetLastWriteTimeUtc(Properties));
            }
            internal void RestoreProfileTimes(string directory)
            {
                foreach (var pair in ProfileTimes)
                    File.SetLastWriteTimeUtc(Path.Combine(directory, pair.Key + ".json"), new DateTime(pair.Value, DateTimeKind.Utc));
            }
            internal void CopyExtras(string home)
            {
                foreach (var pair in Extras) {
                    string file = Path.Combine(home, pair.Key.Replace('/', Path.DirectorySeparatorChar));
                    Directory.CreateDirectory(Path.GetDirectoryName(file));
                    File.WriteAllText(file, (string)pair.Value, PortableConfig.ExtraEncoding(pair.Key));
                }
            }
        }

        private static string AppData()
        {
            string value = Environment.GetEnvironmentVariable("APPDATA");
            return Path.GetFullPath(String.IsNullOrWhiteSpace(value)
                ? Environment.GetFolderPath(Environment.SpecialFolder.UserProfile) : value);
        }

        private static bool AllowedRoot(string root)
        {
            root = Path.GetFullPath(root).TrimEnd(Path.DirectorySeparatorChar);
            return String.Equals(root, Path.Combine(AppData(), ".moons"), StringComparison.OrdinalIgnoreCase)
                || String.Equals(root, Path.Combine(AppData(), ".tempos"), StringComparison.OrdinalIgnoreCase);
        }

        internal static Candidate Capture()
        {
            var candidate = new Candidate();
            candidate.Models = LegacyModelMigration.Capture(AppData());
            var profiles = new Dictionary<string, object>(StringComparer.OrdinalIgnoreCase);
            var profileOwners = new Dictionary<string, Source>(StringComparer.OrdinalIgnoreCase);
            var extras = new Dictionary<string, object>(StringComparer.Ordinal);
            var extraOwners = new Dictionary<string, Source>(StringComparer.Ordinal);
            var legacyTexts = new Dictionary<string, object>(StringComparer.Ordinal);
            string selected = "default";
            foreach (string leaf in new[] { ".moons", ".tempos" }) {
                string root = Path.Combine(AppData(), leaf);
                if (!Directory.Exists(root) || PortableConfig.IsLink(root)) continue;
                var source = new Source { Root = root };
                var importedNames = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
                string directory = Path.Combine(root, "config", "profiles");
                if (Directory.Exists(directory) && !PortableConfig.IsLink(directory)) {
                    foreach (string file in Directory.GetFiles(directory, "*.json")) {
                        string name = Path.GetFileNameWithoutExtension(file);
                        if (!PortableConfig.ValidName(name) || PortableConfig.IsLink(file)) { source.SafeToDelete = false; continue; }
                        name = PortableConfig.CanonicalName(name);
                        if (new FileInfo(file).Length > 2000000) throw new IOException("Legacy profile is too large.");
                        byte[] bytes = File.ReadAllBytes(file);
                        source.CapturedFiles[Relative(root, file)] = PortableConfig.Digest(bytes);
                        var profile = PortableConfig.Profile(PortableConfig.Json().DeserializeObject(new UTF8Encoding(false, true).GetString(bytes).TrimStart('\ufeff')));
                        string actualName = AvailableName(profiles, name, profile);
                        if (!profiles.ContainsKey(actualName)) profiles.Add(actualName, profile);
                        candidate.ProfileTimes[actualName] = File.GetLastWriteTimeUtc(file).Ticks;
                        profileOwners[actualName] = source;
                        importedNames[name] = actualName;
                        source.ParameterFiles.Add(Relative(root, file));
                    }
                }
                string pointer = Path.Combine(root, "config", "selected-profile.info");
                string preferred = "default";
                if (File.Exists(pointer) && !PortableConfig.IsLink(pointer)) {
                    byte[] bytes = File.ReadAllBytes(pointer);
                    source.CapturedFiles[Relative(root, pointer)] = PortableConfig.Digest(bytes);
                    preferred = new UTF8Encoding(false, true).GetString(bytes).TrimStart('\ufeff').Trim();
                }
                if (preferred.EndsWith(".json", StringComparison.OrdinalIgnoreCase)) preferred = preferred.Substring(0, preferred.Length - 5);
                string imported;
                if (importedNames.TryGetValue(preferred, out imported)) selected = imported;

                string ysm = Path.Combine(root, "data", "ysm");
                string settings = Path.Combine(ysm, "ysm.properties");
                CaptureExtra(source, root, settings, "data/ysm/ysm.properties", extras, extraOwners);
                string parameters = Path.Combine(ysm, "parameters");
                if (Directory.Exists(parameters) && !PortableConfig.IsLink(parameters))
                    foreach (string file in Directory.GetFiles(parameters))
                        CaptureExtra(source, root, file, "data/ysm/parameters/" + Path.GetFileName(file), extras, extraOwners);
                foreach (string relative in new[] { "config/server", "config/profiles/server" }) {
                    string folder = Path.Combine(root, relative.Replace('/', Path.DirectorySeparatorChar));
                    if (!Directory.Exists(folder) || PortableConfig.IsLink(folder)) continue;
                    foreach (string file in Directory.GetFiles(folder, "*.json"))
                        CaptureExtra(source, root, file, relative + "/" + Path.GetFileName(file), extras, extraOwners);
                }
                string models = Path.Combine(ysm, "models");
                if (Directory.Exists(models)) candidate.ModelDirectories.Add(models);

                foreach (string name in new[] { "moons.properties", "tempos.properties" }) {
                    string file = Path.Combine(root, "config", name);
                    if (!File.Exists(file) || PortableConfig.IsLink(file)) continue;
                    if (new FileInfo(file).Length > 2000000) throw new IOException("Legacy settings are too large.");
                    string archived = "config/legacy/" + leaf.Substring(1) + "-" + name;
                    byte[] bytes = File.ReadAllBytes(file);
                    source.CapturedFiles[Relative(root, file)] = PortableConfig.Digest(bytes);
                    legacyTexts.Add(archived, PortableConfig.ExtraEncoding(archived).GetString(bytes));
                    source.ParameterFiles.Add(Relative(root, file));
                    if (candidate.Properties == null
                            || File.GetLastWriteTimeUtc(file) > File.GetLastWriteTimeUtc(candidate.Properties)) candidate.Properties = file;
                }
                candidate.Sources.Add(source);
            }
            foreach (Source source in candidate.Sources) {
                foreach (var copy in candidate.Models.Copies)
                    if (copy.Key.StartsWith(source.Root + Path.DirectorySeparatorChar, StringComparison.OrdinalIgnoreCase))
                        source.ParameterFiles.Add(Relative(source.Root, copy.Key));
                CaptureLegacyCopies(source, candidate.Models);
            }
            var origins = new Dictionary<string, string>(StringComparer.Ordinal);
            foreach (var pair in extraOwners) origins.Add(pair.Key, pair.Value.Root);
            candidate.Models.RemapParameters(extras, origins);
            // Preserve every original properties file separately, without remapping or reapplying it.
            foreach (var pair in legacyTexts) extras.Add(pair.Key, pair.Value);
            foreach (var pair in profiles) {
                var profile = PortableConfig.Object(pair.Value, "profile");
                object raw;
                if (!profile.TryGetValue("values", out raw)) continue;
                var values = PortableConfig.Object(raw, "values");
                object current;
                string scan = values.TryGetValue("ysm.scan.directory", out current) ? current as string : null;
                if (String.IsNullOrWhiteSpace(scan) || DefaultModelFolder(scan, candidate.Sources)) {
                    Source owner;
                    string destination = candidate.Models.Destination;
                    if (profileOwners.TryGetValue(pair.Key, out owner) && candidate.Models.BlockedRoots.Contains(owner.Root)) {
                        string nested = Path.Combine(owner.Root, "data", "ysm", "models");
                        destination = Directory.Exists(nested) ? nested : Path.Combine(owner.Root, "models");
                    }
                    values["ysm.scan.directory"] = destination;
                } else values["ysm.scan.directory"] = candidate.Models.RewriteText(scan,
                    profileOwners.ContainsKey(pair.Key) ? profileOwners[pair.Key].Root : null);
            }
            foreach (Source source in candidate.Sources) {
                if (candidate.Models.BlockedRoots.Contains(source.Root)) source.SafeToDelete = false;
                Snapshot(source);
            }
            if (profiles.Count == 0 && candidate.Properties == null && extras.Count == 0 && candidate.Models.Copies.Count == 0) return null;
            foreach (var pair in extras) candidate.Extras.Add(pair.Key, pair.Value);
            if (profiles.Count != 0) {
                if (!profiles.ContainsKey("default")) profiles.Add("default", profiles[selected]);
                candidate.Bundle = PortableConfig.Encode(PortableConfig.NewBundle(selected, profiles, extras));
                PortableConfig.Bundle(candidate.Bundle);
            }
            return candidate;
        }

        private static bool DefaultModelFolder(string path, List<Source> sources)
        {
            try {
                path = Path.GetFullPath(path).TrimEnd(Path.DirectorySeparatorChar);
                foreach (Source source in sources)
                    if (String.Equals(path, Path.Combine(source.Root, "models"), StringComparison.OrdinalIgnoreCase)
                        || String.Equals(path, Path.Combine(source.Root, "data", "ysm"), StringComparison.OrdinalIgnoreCase)
                        || String.Equals(path, Path.Combine(source.Root, "data", "ysm", "models"), StringComparison.OrdinalIgnoreCase)) return true;
            } catch (ArgumentException) { }
            return false;
        }

        private static string AvailableName(Dictionary<string, object> profiles, string name, object value)
        {
            object existing;
            if (!profiles.TryGetValue(name, out existing)
                || PortableConfig.Digest(PortableConfig.Encode(existing)) == PortableConfig.Digest(PortableConfig.Encode(value))) return name;
            string prefix = name.Length > 32 ? name.Substring(0, 32) : name;
            for (int number = 1; ; number++) {
                string next = prefix + "_imported" + number;
                if (!profiles.ContainsKey(next)) return next;
                if (PortableConfig.Digest(PortableConfig.Encode(profiles[next])) == PortableConfig.Digest(PortableConfig.Encode(value))) return next;
            }
        }

        internal static byte[] Merge(byte[] current, Candidate legacy)
        {
            if (current == null) return legacy.Bundle;
            var saved = PortableConfig.Bundle(current);
            var profiles = PortableConfig.Object(saved["profiles"], "profiles");
            var extras = PortableConfig.Object(saved["extras"], "extras");
            if (legacy.Bundle != null) {
                var imported = PortableConfig.Object(PortableConfig.Bundle(legacy.Bundle)["profiles"], "legacy profiles");
                foreach (var pair in imported) {
                    string name = AvailableName(profiles, pair.Key, pair.Value);
                    if (!profiles.ContainsKey(name)) profiles.Add(name, pair.Value);
                }
            }
            foreach (var pair in legacy.Extras) {
                object previous;
                if (!extras.TryGetValue(pair.Key, out previous)) extras.Add(pair.Key, pair.Value);
                else if (!String.Equals((string)previous, (string)pair.Value, StringComparison.Ordinal)) {
                    string key = "config/legacy/conflicts/" + PortableConfig.Digest(PortableConfig.Encode(new[] { pair.Key, (string)pair.Value })) + ".json";
                    extras[key] = PortableConfig.Json().Serialize(new Dictionary<string, object> {
                        { "path", pair.Key }, { "text", pair.Value }, { "encoding", PortableConfig.ExtraEncoding(pair.Key).WebName } });
                }
            }
            byte[] result = PortableConfig.Encode(PortableConfig.NewBundle((string)saved["selected"], profiles, extras));
            PortableConfig.Bundle(result);
            return result;
        }

        private static void CaptureLegacyCopies(Source source, LegacyModelMigration.Result models)
        {
            string legacy = Path.Combine(source.Root, "legacy");
            if (!Directory.Exists(legacy) || PortableConfig.IsLink(legacy)) return;
            foreach (string view in Directory.GetDirectories(legacy)) {
                string marker = Path.Combine(view, "view.sha256");
                if (!System.Text.RegularExpressions.Regex.IsMatch(Path.GetFileName(view), "^[0-9a-f]{16}$")
                    || PortableConfig.IsLink(view) || !File.Exists(marker) || PortableConfig.IsLink(marker)
                    || !System.Text.RegularExpressions.Regex.IsMatch(File.ReadAllText(marker).Trim(), "^[0-9a-f]{64}$")) continue;
                bool hasModels = false, verifiedModels = true;
                var pending = new Stack<string>(); pending.Push(view);
                while (pending.Count != 0) foreach (string file in Directory.GetFileSystemEntries(pending.Pop())) {
                    if (PortableConfig.IsLink(file)) {
                        string linkTarget;
                        if (KnownLegacyDataLink(source.Root, file, out linkTarget)) {
                            string originalData = Path.Combine(source.Root, "data") + Path.DirectorySeparatorChar;
                            foreach (var copy in models.Copies) if (copy.Key.StartsWith(originalData, StringComparison.OrdinalIgnoreCase))
                                models.AddAlias(Path.Combine(file, copy.Key.Substring(originalData.Length)), copy.Value, source.Root);
                            models.AddAlias(Path.Combine(file, "ysm", "models"), models.Destination, source.Root);
                        } else verifiedModels = false;
                        continue;
                    }
                    if (Directory.Exists(file)) { pending.Push(file); continue; }
                    string relative = Relative(view, file);
                    bool model = relative.StartsWith("data/ysm/models/", StringComparison.OrdinalIgnoreCase);
                    hasModels |= model;
                    string original = Path.Combine(source.Root, relative.Replace('/', Path.DirectorySeparatorChar));
                    string expected;
                    bool known = source.ParameterFiles.Contains(relative) && File.Exists(original) && !PortableConfig.IsLink(original);
                    if (!known) { if (model) verifiedModels = false; continue; }
                    if (!source.CapturedFiles.TryGetValue(relative, out expected)) expected = PortableConfig.FileDigest(original);
                    string actual = PortableConfig.FileDigest(file);
                    if (!String.Equals(expected, actual, StringComparison.Ordinal)) { if (model) verifiedModels = false; continue; }
                    string stored = Relative(source.Root, file);
                    source.ParameterFiles.Add(stored); source.CapturedFiles[stored] = actual;
                    string target;
                    if (models.Copies.TryGetValue(original, out target)) models.AddAlias(file, target, source.Root);
                }
                if (hasModels && verifiedModels) models.AddAlias(Path.Combine(view, "data", "ysm", "models"), models.Destination, source.Root);
            }
        }

        private static string Relative(string root, string file)
        { return file.Substring(root.Length + 1).Replace(Path.DirectorySeparatorChar, '/'); }

        private static void CaptureExtra(Source source, string root, string file, string relative,
            Dictionary<string, object> extras, Dictionary<string, Source> owners)
        {
            if (!File.Exists(file)) return;
            if (!PortableConfig.ValidExtra(relative) || PortableConfig.IsLink(file)) { source.SafeToDelete = false; return; }
            if (new FileInfo(file).Length > 2000000) throw new IOException("Legacy model parameters are too large.");
            byte[] bytes = File.ReadAllBytes(file);
            source.CapturedFiles[Relative(root, file)] = PortableConfig.Digest(bytes);
            string text = new UTF8Encoding(false, true).GetString(bytes).TrimStart('\ufeff');
            if (relative.EndsWith(".json", StringComparison.Ordinal)) PortableConfig.Json().DeserializeObject(text);
            object previous;
            if (extras.TryGetValue(relative, out previous) && !String.Equals((string)previous, text, StringComparison.Ordinal)) {
                source.SafeToDelete = false;
                owners[relative].SafeToDelete = false;
            }
            extras[relative] = text;
            owners[relative] = source;
            source.ParameterFiles.Add(Relative(root, file));
        }

        private static bool Generated(string relative)
        {
            var view = System.Text.RegularExpressions.Regex.Match(relative, "^legacy/[0-9a-f]{16}/(.+)$");
            if (view.Success) {
                relative = view.Groups[1].Value;
                if (relative == "view.sha256") return true;
            }
            return relative.StartsWith("cache/", StringComparison.OrdinalIgnoreCase)
                || relative.StartsWith("libraries/latest/", StringComparison.OrdinalIgnoreCase)
                || relative.StartsWith("libraries/legacy/", StringComparison.OrdinalIgnoreCase)
                || System.Text.RegularExpressions.Regex.IsMatch(relative, "^libraries/(?:objects/[0-9a-f]{64}\\.jar|[0-9a-f]{64}/(?:moons|tempos)-ui-runtime\\.jar|(?:moons|tempos)-(?:ui-runtime\\.current|dependencies\\.properties|ysm-(?:core|codecs|images)\\.jar))$", System.Text.RegularExpressions.RegexOptions.IgnoreCase)
                || System.Text.RegularExpressions.Regex.IsMatch(relative, "^modules/(?:moons|tempos)-ysm-[0-9A-Za-z._-]+\\.jar$", System.Text.RegularExpressions.RegexOptions.IgnoreCase)
                || relative.StartsWith("installations/", StringComparison.OrdinalIgnoreCase)
                || relative.StartsWith("logs/", StringComparison.OrdinalIgnoreCase)
                || relative == "bridge-dll.log" || relative == "bridge.log" || relative == "native-agent.txt"
                || relative == "data/ysm-diagnostic/snapshot.txt" || relative == ".import-complete-v1"
                || relative == "config/.import-complete-v1" || relative == "data/ysm/.import-complete-v1"
                || System.Text.RegularExpressions.Regex.IsMatch(relative, "^bridge-[0-9]+\\.(?:conf|properties)$")
                || relative == "config/selected-profile.info";
        }

        private static void Snapshot(Source source)
        {
            var pending = new Stack<string>(); pending.Push(source.Root);
            while (pending.Count != 0) {
                foreach (string path in Directory.GetFileSystemEntries(pending.Pop())) {
                    if (PortableConfig.IsLink(path)) {
                        string target;
                        if (KnownLegacyDataLink(source.Root, path, out target)) source.Links.Add(Relative(source.Root, path), target);
                        else source.SafeToDelete = false;
                        continue;
                    }
                    if (Directory.Exists(path)) { pending.Push(path); continue; }
                    string relative = Relative(source.Root, path);
                    if (!source.ParameterFiles.Contains(relative) && !Generated(relative)) source.SafeToDelete = false;
                    try {
                        string actual = PortableConfig.FileDigest(path), captured;
                        if (source.CapturedFiles.TryGetValue(relative, out captured)
                            && !String.Equals(captured, actual, StringComparison.Ordinal)) source.SafeToDelete = false;
                        source.Files.Add(relative, actual);
                    }
                    catch (IOException) { source.SafeToDelete = false; }
                    catch (UnauthorizedAccessException) { source.SafeToDelete = false; }
                }
            }
        }

        private static bool KnownLegacyDataLink(string root, string path, out string target)
        {
            target = null;
            if (!path.StartsWith(root + Path.DirectorySeparatorChar, StringComparison.OrdinalIgnoreCase)
                || !System.Text.RegularExpressions.Regex.IsMatch(Relative(root, path), "^legacy/[0-9a-f]{16}/data$")) return false;
            string view = Path.GetDirectoryName(path), data = Path.Combine(root, "data"), marker = Path.Combine(view, "view.sha256");
            if (PortableConfig.IsLink(Path.Combine(root, "legacy")) || PortableConfig.IsLink(view)
                || !Directory.Exists(data) || PortableConfig.IsLink(data) || !Directory.Exists(path) || !PortableConfig.IsLink(path)
                || !File.Exists(marker) || PortableConfig.IsLink(marker)
                || !System.Text.RegularExpressions.Regex.IsMatch(File.ReadAllText(marker).Trim(), "^[0-9a-f]{64}$")) return false;
            try {
                string resolved = LegacyModelMigration.Canonical(path), expected = LegacyModelMigration.Canonical(data);
                if (!String.Equals(resolved, expected, StringComparison.OrdinalIgnoreCase)) return false;
                target = resolved;
                return true;
            } catch (IOException) { return false; }
            catch (UnauthorizedAccessException) { return false; }
        }

        internal static bool Complete(object metadata, Func<bool> oldGameRunning = null)
        {
            if (metadata == null) return true;
            if (oldGameRunning != null && oldGameRunning()) return false;
            var root = PortableConfig.Object(metadata, "legacy migration metadata");
            object modelMetadata;
            if (root.TryGetValue("models", out modelMetadata) && modelMetadata != null && !LegacyModelMigration.Verify(modelMetadata)) return false;
            var sources = root["sources"] as object[];
            if (sources == null) throw new InvalidDataException("Invalid legacy source metadata.");
            bool completed = true;
            foreach (object item in sources) {
                var source = PortableConfig.Object(item, "legacy source");
                string path = Path.GetFullPath((string)source["root"]).TrimEnd(Path.DirectorySeparatorChar);
                if (!AllowedRoot(path) || !(bool)source["safe"]) { completed = false; continue; }
                if (!Directory.Exists(path)) continue;
                if (PortableConfig.IsLink(path)) { completed = false; continue; }
                if (oldGameRunning == null && LegacyBridgeRunning(path)) { completed = false; continue; }
                var expected = PortableConfig.Object(source["files"], "legacy file identities");
                object rawLinks;
                var expectedLinks = source.TryGetValue("links", out rawLinks) ? PortableConfig.Object(rawLinks, "legacy junction identities") : new Dictionary<string, object>();
                var actual = new Source { Root = path };
                foreach (string name in expected.Keys) actual.ParameterFiles.Add(name);
                // Read every file without following links before authorizing the root deletion.
                Snapshot(actual);
                if (!actual.SafeToDelete || actual.Files.Count != expected.Count || actual.Links.Count != expectedLinks.Count) { completed = false; continue; }
                bool matches = true;
                foreach (var pair in expected) {
                    object value;
                    if (!actual.Files.TryGetValue(pair.Key, out value) || !String.Equals((string)value, (string)pair.Value, StringComparison.Ordinal))
                        matches = false;
                }
                foreach (var pair in expectedLinks) {
                    object value;
                    if (!actual.Links.TryGetValue(pair.Key, out value) || !String.Equals((string)value, (string)pair.Value, StringComparison.OrdinalIgnoreCase)) matches = false;
                }
                if (!matches || (modelMetadata != null && !LegacyModelMigration.Verify(modelMetadata))
                    || (oldGameRunning == null ? LegacyBridgeRunning(path) : oldGameRunning())) { completed = false; continue; }
                // These are verified internal legacy data junctions. Unlink them without recursing.
                foreach (string relative in actual.Links.Keys) Directory.Delete(Path.Combine(path, relative.Replace('/', Path.DirectorySeparatorChar)), false);
                Directory.Delete(path, true);
            }
            return completed;
        }

        private static bool LegacyBridgeRunning(string root)
        {
            foreach (string name in new[] { "java", "javaw" }) {
                foreach (var process in Process.GetProcessesByName(name)) {
                    using (process) {
                        try {
                            foreach (ProcessModule module in process.Modules)
                                if ((String.Equals(module.ModuleName, "moons-bridge.dll", StringComparison.OrdinalIgnoreCase)
                                        || String.Equals(module.ModuleName, "tempos-bridge.dll", StringComparison.OrdinalIgnoreCase))
                                    && Path.GetFullPath(module.FileName).StartsWith(root + Path.DirectorySeparatorChar, StringComparison.OrdinalIgnoreCase)) return true;
                        } catch (InvalidOperationException) { }
                        catch (System.ComponentModel.Win32Exception) { return true; }
                    }
                }
            }
            return false;
        }
    }
}
