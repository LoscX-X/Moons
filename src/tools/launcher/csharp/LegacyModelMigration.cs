using System;
using System.Collections.Generic;
using System.IO;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;
using Microsoft.Win32.SafeHandles;

namespace Tempos.WindowsLauncher
{
    /// <summary>Preserves assets from the two historical homes before their parameter-only migration.</summary>
    internal static class LegacyModelMigration
    {
        private sealed class Alias
        {
            internal string OldPath, NewPath, Root;
        }
        private sealed class ParameterCopy
        { internal string Source, Target, Root; }

        internal sealed class Result
        {
            internal string Destination;
            internal readonly Dictionary<string, string> Copies = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
            internal readonly HashSet<string> BlockedRoots = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
            internal readonly List<string> Notes = new List<string>();
            private readonly Dictionary<string, string> hashes = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
            private readonly List<Alias> aliases = new List<Alias>();
            internal string AppDataPath;

            internal void RemapParameters(Dictionary<string, object> extras)
            { RemapParameters(extras, null); }

            internal void RemapParameters(Dictionary<string, object> extras, Dictionary<string, string> origins)
            {
                // Take the old keys once: a generated alias must not become the source of another import.
                var original = new Dictionary<string, object>(extras, StringComparer.Ordinal);
                var copies = new List<ParameterCopy>();
                foreach (Alias alias in aliases) {
                    string oldKey = "data/ysm/parameters/" + ParameterKey(alias.OldPath) + ".json";
                    string newKey = "data/ysm/parameters/" + ParameterKey(alias.NewPath) + ".json";
                    foreach (var pair in original) {
                        if (!pair.Key.StartsWith(oldKey, StringComparison.OrdinalIgnoreCase)
                                || !pair.Key.EndsWith(".json", StringComparison.OrdinalIgnoreCase)
                                || !(pair.Value is string)) continue;
                        copies.Add(new ParameterCopy { Source = pair.Key,
                            Target = newKey + pair.Key.Substring(oldKey.Length), Root = alias.Root });
                    }
                }
                // Decide all collisions before changing selected paths or adding any target UUID.
                // Recheck after blocking a root, since values can contain paths to another model.
                int previousBlocked;
                do {
                    previousBlocked = BlockedRoots.Count;
                    var planned = new Dictionary<string, string>(StringComparer.Ordinal);
                    var owners = new Dictionary<string, HashSet<string>>(StringComparer.Ordinal);
                    var conflicts = new HashSet<string>(StringComparer.Ordinal);
                    foreach (ParameterCopy copy in copies) {
                        if (BlockedRoots.Contains(copy.Root)) continue;
                        HashSet<string> roots;
                        if (!owners.TryGetValue(copy.Target, out roots)) {
                            owners[copy.Target] = roots = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
                            object existing;
                            if (original.TryGetValue(copy.Target, out existing)) {
                                planned[copy.Target] = RewriteText(existing as string, Origin(origins, copy.Target));
                                string owner = Origin(origins, copy.Target);
                                if (owner != null) roots.Add(owner);
                            }
                        }
                        roots.Add(copy.Root);
                        string value = RewriteText((string)original[copy.Source], Origin(origins, copy.Source));
                        string existingValue;
                        if (planned.TryGetValue(copy.Target, out existingValue)
                                && !String.Equals(existingValue, value, StringComparison.Ordinal)) conflicts.Add(copy.Target);
                        else planned[copy.Target] = value;
                    }
                    foreach (string target in conflicts)
                        foreach (string root in owners[target]) Block(root, "Model parameter conflict: " + target);
                } while (previousBlocked != BlockedRoots.Count);

                foreach (var pair in original) {
                    string text = pair.Value as string;
                    if (text != null) extras[pair.Key] = RewriteText(text, Origin(origins, pair.Key));
                }
                foreach (ParameterCopy copy in copies)
                    if (!BlockedRoots.Contains(copy.Root) && !extras.ContainsKey(copy.Target))
                        extras.Add(copy.Target, extras[copy.Source]);
            }

            private static string Origin(Dictionary<string, string> origins, string key)
            { string root; return origins != null && origins.TryGetValue(key, out root) ? root : null; }

            internal string RewriteText(string text)
            { return RewriteText(text, null); }

            internal string RewriteText(string text, string originRoot)
            {
                if (String.IsNullOrEmpty(text)) return text;
                string trimmed = text.TrimStart('\ufeff', ' ', '\t', '\r', '\n');
                if (trimmed.StartsWith("{", StringComparison.Ordinal) || trimmed.StartsWith("[", StringComparison.Ordinal)) {
                    // Replace string values in place, retaining numbers and every unrelated JSON token exactly.
                    return Regex.Replace(text, "\"(?:\\\\.|[^\"\\\\])*\"", match => {
                        int end = match.Index + match.Length;
                        while (end < text.Length && Char.IsWhiteSpace(text[end])) end++;
                        if (end < text.Length && text[end] == ':') return match.Value;
                        string value = PortableConfig.Json().DeserializeObject(match.Value) as string;
                        string replacement = RewritePath(value, originRoot);
                        return value == replacement ? match.Value : PortableConfig.Json().Serialize(replacement);
                    });
                }
                string direct = RewritePath(text, originRoot);
                if (direct != text) return direct;
                // java.util.Properties supports escaped separators, unicode, and continued logical lines.
                var result = new StringBuilder();
                using (var reader = new StringReader(text)) {
                    string line;
                    while ((line = reader.ReadLine()) != null) {
                        string original = line, logical = line;
                        while (Continued(logical)) {
                            string next = reader.ReadLine();
                            if (next == null) break;
                            original += "\n" + next;
                            logical = logical.Substring(0, logical.Length - 1) + next.TrimStart(' ', '\t', '\f');
                        }
                        int start = 0;
                        while (start < logical.Length && PropertySpace(logical[start])) start++;
                        if (start == logical.Length || logical[start] == '#' || logical[start] == '!') {
                            result.Append(original).Append('\n'); continue;
                        }
                        int split = start;
                        while (split < logical.Length) {
                            if (logical[split] == '\\') { split += Math.Min(2, logical.Length - split); continue; }
                            if (logical[split] == '=' || logical[split] == ':' || PropertySpace(logical[split])) break;
                            split++;
                        }
                        int valueStart = split;
                        while (valueStart < logical.Length && PropertySpace(logical[valueStart])) valueStart++;
                        if (valueStart < logical.Length && (logical[valueStart] == '=' || logical[valueStart] == ':')) valueStart++;
                        while (valueStart < logical.Length && PropertySpace(logical[valueStart])) valueStart++;
                        string value = PropertyValue(logical.Substring(valueStart));
                        string replacement = value == null ? null : RewritePath(value, originRoot);
                        result.Append(value == replacement ? original
                            : logical.Substring(0, valueStart) + EscapeProperty(replacement)).Append('\n');
                    }
                }
                // Keep byte-for-byte text when there was no path change (including its original newline style).
                string rewritten = result.ToString();
                if (NormalizeLines(text).TrimEnd('\n') == rewritten.TrimEnd('\n')) return text;
                return text.IndexOf("\r\n", StringComparison.Ordinal) >= 0 ? rewritten.Replace("\n", "\r\n") : rewritten;
            }

            private string RewritePath(string value, string originRoot)
            {
                if (String.IsNullOrEmpty(value)) return value;
                string path = value.Replace('/', '\\');
                Alias best = null;
                foreach (Alias alias in aliases) {
                    if (String.Equals(path, alias.OldPath, StringComparison.OrdinalIgnoreCase)) {
                        if (BlockedRoots.Contains(alias.Root)) return value;
                        if (best == null || alias.OldPath.Length > best.OldPath.Length) best = alias;
                    }
                }
                if (best != null) return best.NewPath + path.Substring(best.OldPath.Length);
                if (path.Equals("models", StringComparison.OrdinalIgnoreCase)
                        || path.StartsWith("models\\", StringComparison.OrdinalIgnoreCase)) {
                    if (originRoot != null && BlockedRoots.Contains(originRoot)) {
                        string source = Path.GetFullPath(Path.Combine(originRoot, "data", "ysm", path));
                        string models = Path.Combine(originRoot, "data", "ysm", "models");
                        if (String.Equals(source, models, StringComparison.OrdinalIgnoreCase) || Below(models, source)) {
                            if (File.Exists(source) || Directory.Exists(source)) {
                                EnsureUnlinked(originRoot, source);
                                return source;
                            }
                        }
                        return value;
                    }
                    string replacement = null;
                    var roots = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
                    foreach (Alias alias in aliases) {
                        if (originRoot != null && !String.Equals(originRoot, alias.Root, StringComparison.OrdinalIgnoreCase)) continue;
                        string oldYsm = Path.Combine(alias.Root, "data", "ysm");
                        if (!Below(oldYsm, alias.OldPath)) continue;
                        string oldRelative = alias.OldPath.Substring(oldYsm.Length + 1);
                        if (!String.Equals(path, oldRelative, StringComparison.OrdinalIgnoreCase)) continue;
                        string candidate = alias.NewPath + path.Substring(oldRelative.Length);
                        roots.Add(alias.Root);
                        if (replacement == null) replacement = candidate;
                        else if (!String.Equals(replacement, candidate, StringComparison.OrdinalIgnoreCase)) replacement = "";
                    }
                    // Relative settings have no source-home identity after extras are merged. Do not
                    // guess when any model-tree conflict could make the same spelling ambiguous.
                    if (replacement != null && replacement.Length != 0
                            && (originRoot != null || BlockedRoots.Count == 0)) return replacement;
                    foreach (string root in roots) Block(root, "Relative model path could not be remapped unambiguously: " + value);
                }
                return value;
            }

            internal object Metadata()
            {
                var files = new List<object>();
                foreach (var pair in Copies) files.Add(new Dictionary<string, object> {
                    { "source", pair.Key }, { "target", pair.Value }, { "sha256", hashes[pair.Key] } });
                return new Dictionary<string, object> {
                    { "appData", AppDataPath }, { "destination", Destination }, { "files", files.ToArray() } };
            }

            internal void Block(string root, string reason)
            { BlockedRoots.Add(root); Notes.Add(reason); }

            internal void AddAlias(string source, string target, string root)
            {
                string sourceFull = Path.GetFullPath(source), targetFull = Path.GetFullPath(target);
                string sourceCanonical = Canonical(source), targetCanonical = Canonical(target);
                // Both historical normalized Paths and real-path spellings can have parameter UUIDs.
                foreach (string oldPath in new[] { sourceFull, sourceCanonical })
                    foreach (string newPath in new[] { targetFull, targetCanonical }) {
                        bool exists = false;
                        foreach (Alias previous in aliases)
                            if (previous.OldPath == oldPath && previous.NewPath == newPath) { exists = true; break; }
                        if (!exists) aliases.Add(new Alias {
                            OldPath = oldPath, NewPath = newPath, Root = root });
                    }
            }

            internal bool CopyTree(string source, string target, string root)
            {
                bool complete = true;
                try {
                    if (PortableConfig.IsLink(source)) throw new IOException("Linked model directory.");
                    EnsureDirectory(Destination, target);
                    foreach (string entry in Directory.GetFileSystemEntries(source)) {
                        string next = Path.Combine(target, Path.GetFileName(entry));
                        try {
                            if (PortableConfig.IsLink(entry)) throw new IOException("Linked model asset.");
                            if (Directory.Exists(entry)) {
                                if (!CopyTree(entry, next, root)) complete = false;
                            } else {
                                string hash = CopyFile(entry, next);
                                Copies[entry] = next; hashes[entry] = hash;
                                string extension = Path.GetExtension(entry);
                                if (extension.Equals(".ysm", StringComparison.OrdinalIgnoreCase)
                                        || extension.Equals(".zip", StringComparison.OrdinalIgnoreCase)) AddAlias(entry, next, root);
                            }
                        } catch (IOException failure) { complete = false; Block(root, entry + ": " + failure.Message); }
                        catch (UnauthorizedAccessException failure) { complete = false; Block(root, entry + ": " + failure.Message); }
                    }
                    if (complete) AddAlias(source, target, root);
                } catch (IOException failure) { complete = false; Block(root, source + ": " + failure.Message); }
                catch (UnauthorizedAccessException failure) { complete = false; Block(root, source + ": " + failure.Message); }
                return complete;
            }
        }

        internal static Result Capture(string appData)
        {
            appData = Path.GetFullPath(appData).TrimEnd(Path.DirectorySeparatorChar);
            var result = new Result { AppDataPath = appData, Destination = Path.Combine(appData, "models") };
            foreach (string leaf in new[] { ".moons", ".tempos" }) {
                string root = Path.Combine(appData, leaf);
                if (!Directory.Exists(root)) continue;
                foreach (string relative in new[] { "models", Path.Combine("data", "ysm", "models") }) {
                    string source = Path.Combine(root, relative);
                    if (!Directory.Exists(source)) continue;
                    try { EnsureUnlinked(root, source); result.CopyTree(source, result.Destination, root); }
                    catch (IOException failure) { result.Block(root, source + ": " + failure.Message); }
                    catch (UnauthorizedAccessException failure) { result.Block(root, source + ": " + failure.Message); }
                }
            }
            return result;
        }

        internal static bool Verify(object metadata)
        {
            if (metadata == null) return true;
            try {
                var data = PortableConfig.Object(metadata, "model migration metadata");
                string appData = Path.GetFullPath((string)data["appData"]);
                string destination = Path.GetFullPath((string)data["destination"]);
                if (!String.Equals(destination, Path.Combine(appData, "models"), StringComparison.OrdinalIgnoreCase)) return false;
                var files = data["files"] as object[];
                if (files == null) return false;
                foreach (object value in files) {
                    var file = PortableConfig.Object(value, "preserved model");
                    string source = Path.GetFullPath((string)file["source"]), target = Path.GetFullPath((string)file["target"]);
                    bool allowed = false;
                    foreach (string leaf in new[] { ".moons", ".tempos" })
                        foreach (string relative in new[] { "models", Path.Combine("data", "ysm", "models") })
                            if (Below(Path.Combine(appData, leaf, relative), source)) allowed = true;
                    if (!allowed || !Below(destination, target) || !File.Exists(target)) return false;
                    EnsureUnlinked(destination, target);
                    string expected = (string)file["sha256"];
                    if (PortableConfig.FileDigest(target) != expected) return false;
                    // The config migrator checks the complete source snapshot separately. A previous
                    // source root may already have been retired during the same completion pass.
                    if (File.Exists(source)) {
                        EnsureUnlinked(appData, source);
                        if (PortableConfig.FileDigest(source) != expected) return false;
                    }
                }
                return true;
            } catch (IOException) { return false; }
            catch (UnauthorizedAccessException) { return false; }
            catch (ArgumentException) { return false; }
            catch (InvalidCastException) { return false; }
            catch (KeyNotFoundException) { return false; }
        }

        private static bool Below(string root, string path)
        { return path.StartsWith(root.TrimEnd(Path.DirectorySeparatorChar) + Path.DirectorySeparatorChar, StringComparison.OrdinalIgnoreCase); }

        private static void EnsureUnlinked(string root, string path)
        {
            root = Path.GetFullPath(root).TrimEnd(Path.DirectorySeparatorChar);
            path = Path.GetFullPath(path).TrimEnd(Path.DirectorySeparatorChar);
            if (!String.Equals(root, path, StringComparison.OrdinalIgnoreCase) && !Below(root, path))
                throw new IOException("Model path leaves its asset tree.");
            for (;;) {
                if (PortableConfig.IsLink(path)) throw new IOException("Linked model path.");
                if (String.Equals(path, root, StringComparison.OrdinalIgnoreCase)) break;
                path = Path.GetDirectoryName(path);
            }
        }

        private static void EnsureDirectory(string root, string directory)
        {
            root = Path.GetFullPath(root);
            directory = Path.GetFullPath(directory);
            if (!String.Equals(root, directory, StringComparison.OrdinalIgnoreCase) && !Below(root, directory))
                throw new IOException("Invalid model destination.");
            string parent = Path.GetDirectoryName(directory);
            if (!String.Equals(root, directory, StringComparison.OrdinalIgnoreCase)) EnsureDirectory(root, parent);
            if (File.Exists(directory)) throw new IOException("A file occupies the model destination directory.");
            if (!Directory.Exists(directory)) Directory.CreateDirectory(directory);
            EnsureUnlinked(root, directory);
        }

        private static string CopyFile(string source, string target)
        {
            string temporary = null;
            try {
                using (var input = new FileStream(source, FileMode.Open, FileAccess.Read, FileShare.Read))
                using (var sha = SHA256.Create()) {
                    string hash = BitConverter.ToString(sha.ComputeHash(input)).Replace("-", "").ToLowerInvariant();
                    if (Directory.Exists(target)) throw new IOException("A directory occupies the model file destination.");
                    if (!File.Exists(target)) {
                        input.Position = 0;
                        temporary = Path.Combine(Path.GetDirectoryName(target), ".tempos-model-" + Guid.NewGuid().ToString("N") + ".tmp");
                        using (var output = new FileStream(temporary, FileMode.CreateNew, FileAccess.Write, FileShare.None)) {
                            input.CopyTo(output); output.Flush(true);
                        }
                        // No replacement: an existing different user model always wins.
                        try { File.Move(temporary, target); temporary = null; }
                        catch (IOException) { if (!File.Exists(target)) throw; }
                    }
                    if (PortableConfig.IsLink(target) || PortableConfig.FileDigest(target) != hash)
                        throw new IOException("Different model content already exists at the destination.");
                    return hash;
                }
            } finally { if (temporary != null && File.Exists(temporary)) File.Delete(temporary); }
        }

        private static string ParameterKey(string path)
        {
            byte[] bytes;
            using (var md5 = MD5.Create()) bytes = md5.ComputeHash(Encoding.UTF8.GetBytes(path));
            bytes[6] = (byte)((bytes[6] & 15) | 48);
            bytes[8] = (byte)((bytes[8] & 63) | 128);
            string hex = BitConverter.ToString(bytes).Replace("-", "").ToLowerInvariant();
            return hex.Substring(0, 8) + "-" + hex.Substring(8, 4) + "-" + hex.Substring(12, 4)
                + "-" + hex.Substring(16, 4) + "-" + hex.Substring(20, 12);
        }

        private static bool PropertySpace(char value) { return value == ' ' || value == '\t' || value == '\f'; }
        private static bool Continued(string value)
        {
            int count = 0;
            for (int index = value.Length - 1; index >= 0 && value[index] == '\\'; index--) count++;
            return count % 2 != 0;
        }
        private static string NormalizeLines(string value) { return value.Replace("\r\n", "\n").Replace('\r', '\n'); }
        private static string PropertyValue(string value)
        {
            var result = new StringBuilder();
            for (int index = 0; index < value.Length; index++) {
                char ch = value[index];
                if (ch != '\\' || index == value.Length - 1) { result.Append(ch); continue; }
                ch = value[++index];
                if (ch == 'u') {
                    int unicode;
                    if (index + 4 >= value.Length || !Int32.TryParse(value.Substring(index + 1, 4),
                            System.Globalization.NumberStyles.HexNumber, System.Globalization.CultureInfo.InvariantCulture, out unicode)) return null;
                    result.Append((char)unicode); index += 4;
                } else result.Append(ch == 't' ? '\t' : ch == 'r' ? '\r' : ch == 'n' ? '\n' : ch == 'f' ? '\f' : ch);
            }
            return result.ToString();
        }
        private static string EscapeProperty(string value)
        {
            var result = new StringBuilder();
            foreach (char ch in value) {
                if (ch == '\\' || ch == ':' || ch == '=' || ch == ' ' || ch == '#' || ch == '!') result.Append('\\').Append(ch);
                else if (ch == '\t') result.Append("\\t");
                else if (ch == '\r') result.Append("\\r");
                else if (ch == '\n') result.Append("\\n");
                else if (ch == '\f') result.Append("\\f");
                else if (ch < 32 || ch > 126) result.Append("\\u").Append(((int)ch).ToString("x4"));
                else result.Append(ch);
            }
            return result.ToString();
        }

        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        private static extern SafeFileHandle CreateFile(string name, uint access, uint share, IntPtr security, uint disposition, uint flags, IntPtr template);
        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        private static extern uint GetFinalPathNameByHandle(SafeFileHandle handle, StringBuilder path, uint length, uint flags);
        internal static string Canonical(string path)
        {
            using (var handle = CreateFile(path, 0, 7, IntPtr.Zero, 3, 0x02000000, IntPtr.Zero)) {
                if (handle.IsInvalid) throw new IOException("Could not resolve canonical model path.");
                var buffer = new StringBuilder(512);
                uint length = GetFinalPathNameByHandle(handle, buffer, (uint)buffer.Capacity, 0);
                if (length >= buffer.Capacity) {
                    buffer.Capacity = checked((int)length + 1);
                    length = GetFinalPathNameByHandle(handle, buffer, (uint)buffer.Capacity, 0);
                }
                if (length == 0 || length >= buffer.Capacity) throw new IOException("Could not resolve canonical model path.");
                string full = buffer.ToString();
                if (full.StartsWith(@"\\?\UNC\", StringComparison.OrdinalIgnoreCase)) return @"\\" + full.Substring(8);
                return full.StartsWith(@"\\?\", StringComparison.Ordinal) ? full.Substring(4) : full;
            }
        }
    }
}
