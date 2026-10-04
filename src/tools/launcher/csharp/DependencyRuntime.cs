using System;
using System.Collections.Generic;
using System.IO;
using System.Reflection;
using System.Text;
using System.Text.RegularExpressions;
using Moons.Shared;

namespace Moons.WindowsLauncher
{
    /// <summary>The shared installation contract. Verification never changes the user's files.</summary>
    internal static class DependencyRuntime
    {
        internal sealed class Context
        {
            internal readonly string Path, Hash;
            internal Context(string path, string hash) { Path = path; Hash = hash; }
        }

        internal static Context Resolve(string home, SupportProfile profile)
        {
            return Resolve(home, profile, Metadata(UiMetadataResource), Metadata(YsmMetadataResource));
        }

        internal static Context Resolve(string home, SupportProfile profile,
            IDictionary<string, string> ui, IDictionary<string, string> ysm)
        {
            if (profile == null) throw new InvalidDataException("Unknown dependency profile.");
            string content = ContextContent(home, profile, ui, ysm);
            string path = System.IO.Path.Combine(ViewRoot(home, ui, ysm), "contexts", profile.ProfileId + ".properties");
            string hash = Hashing.Sha256(Encoding.UTF8.GetBytes(content));
            RequireFile(path, hash);
            RequireFile(System.IO.Path.Combine(home, "libraries", RequiredHash(ui, "sha256"), "moons-ui-runtime.jar"), RequiredHash(ui, "sha256"));
            foreach (string name in RequiredYsm(profile))
                RequireFile(System.IO.Path.Combine(ViewRoot(home, ui, ysm), name.Replace('/', System.IO.Path.DirectorySeparatorChar)), RequiredHash(ysm, name));
            return new Context(path, hash);
        }

        internal static string ViewRoot(string home, IDictionary<string, string> ui, IDictionary<string, string> ysm)
        {
            var identity = new StringBuilder("format=2\nui=" + RequiredHash(ui, "sha256") + "\n");
            foreach (string name in YsmPackageNames) identity.Append(name).Append('=').Append(RequiredHash(ysm, name)).Append('\n');
            return System.IO.Path.Combine(home, "installations", Hashing.Sha256(Encoding.UTF8.GetBytes(identity.ToString())));
        }

        private static string[] RequiredYsm(SupportProfile profile)
        {
            return new[] { "libraries/moons-ysm-core.jar", "libraries/moons-ysm-codecs.jar", "libraries/moons-ysm-images.jar", profile.YsmModuleName };
        }

        private static string ContextContent(string home, SupportProfile profile,
            IDictionary<string, string> ui, IDictionary<string, string> ysm)
        {
            string relative = "installations/" + System.IO.Path.GetFileName(ViewRoot(home, ui, ysm));
            var content = new StringBuilder("format=2\nprofile=" + profile.GameId + "\njava.minimum=" + profile.JavaVersion + "\n");
            content.Append("root=").Append(relative).Append('\n');
            content.Append("ui=libraries/").Append(RequiredHash(ui, "sha256")).Append("/moons-ui-runtime.jar\n");
            content.Append("ui.sha256=").Append(RequiredHash(ui, "sha256")).Append('\n');
            content.Append("module=").Append(profile.YsmModuleName).Append('\n');
            foreach (string name in RequiredYsm(profile)) content.Append(name).Append(".sha256=").Append(RequiredHash(ysm, name)).Append('\n');
            return content.ToString();
        }

        // Publish context manifests last, after every referenced byte has been verified.
        internal static void PublishViews(string home)
        {
            PublishViews(home, Metadata(UiMetadataResource), Metadata(YsmMetadataResource));
        }

        internal static void PublishViews(string home, IDictionary<string, string> ui, IDictionary<string, string> ysm)
        {
            string root = ViewRoot(home, ui, ysm);
            RequireFile(System.IO.Path.Combine(home, "libraries", RequiredHash(ui, "sha256"), "moons-ui-runtime.jar"), RequiredHash(ui, "sha256"));
            foreach (string name in YsmPackageNames)
            {
                string source = System.IO.Path.Combine(home, name.Replace('/', System.IO.Path.DirectorySeparatorChar));
                string hash = RequiredHash(ysm, name);
                RequireFile(source, hash);
                string target = System.IO.Path.Combine(root, name.Replace('/', System.IO.Path.DirectorySeparatorChar));
                PublishExpected(target, hash, temporary => File.Copy(source, temporary, true));
            }
            foreach (var profile in VersionCatalog.Profiles)
            {
                string content = ContextContent(home, profile, ui, ysm);
                string target = System.IO.Path.Combine(root, "contexts", profile.ProfileId + ".properties");
                PublishExpected(target, Hashing.Sha256(Encoding.UTF8.GetBytes(content)),
                    temporary => File.WriteAllText(temporary, content, new UTF8Encoding(false)));
            }
            PublishLegacyView(home, ui, ysm);
        }

        internal static string LegacyView(string home, IDictionary<string, string> ui, IDictionary<string, string> ysm)
        {
            // Old .NET launchers have a 260-character path budget; keep the full identity in the view.
            return System.IO.Path.Combine(home, "legacy", DependencyIdentity(ui, ysm).Substring(0, 16));
        }

        private static string DependencyIdentity(IDictionary<string, string> ui, IDictionary<string, string> ysm)
        {
            var keys = new List<string>(ysm.Keys); keys.Sort(StringComparer.Ordinal);
            var content = new StringBuilder("legacy=1\nui=" + RequiredHash(ui, "sha256") + "\n");
            foreach (string name in keys) content.Append(name).Append('=').Append(RequiredHash(ysm, name)).Append('\n');
            return Hashing.Sha256(Encoding.UTF8.GetBytes(content.ToString()));
        }

        // Capture the installed pre-context layout before updating any shared file.
        internal static void ArchiveInstalledLegacy(string home)
        {
            string pointer = System.IO.Path.Combine(home, "libraries", "moons-ui-runtime.current");
            if (!File.Exists(pointer)) return;
            string relative = File.ReadAllText(pointer).Trim();
            if (!Regex.IsMatch(relative, "^[0-9a-f]{64}/moons-ui-runtime.jar$")) return;
            var ui = new Dictionary<string, string> { { "sha256", relative.Substring(0, 64) } };
            string uiFile = System.IO.Path.Combine(home, "libraries", relative.Replace('/', System.IO.Path.DirectorySeparatorChar));
            if (!File.Exists(uiFile) || Hashing.Sha256(uiFile) != ui["sha256"]) return;
            var ysm = new Dictionary<string, string>(StringComparer.Ordinal);
            string installedRecord = System.IO.Path.Combine(home, "libraries", "moons-dependencies.properties");
            var previousMetadata = File.Exists(installedRecord) ? RuntimeMetadata.Parse(File.ReadAllText(installedRecord))
                : new Dictionary<string, string>();
            foreach (string name in new[] { "libraries/moons-ysm-core.jar", "libraries/moons-ysm-codecs.jar", "libraries/moons-ysm-images.jar" }) {
                string file = System.IO.Path.Combine(home, name.Replace('/', System.IO.Path.DirectorySeparatorChar));
                if (!File.Exists(file)) return;
                if (!CanArchive(file, name, previousMetadata)) return;
                ysm.Add(name, Hashing.Sha256(file));
            }
            string modules = System.IO.Path.Combine(home, "modules");
            if (!Directory.Exists(modules)) return;
            foreach (string file in Directory.GetFiles(modules, "moons-ysm-*.jar")) {
                string name = System.IO.Path.GetFileName(file);
                if (Regex.IsMatch(name, "^moons-ysm-[A-Za-z0-9._-]+\\.jar$")) {
                    if (!CanArchive(file, "modules/" + name, previousMetadata)) return;
                    ysm.Add("modules/" + name, Hashing.Sha256(file));
                }
            }
            if (ysm.Count == 3) return;
            PublishLegacyView(home, ui, ysm);
        }

        private static bool CanArchive(string file, string name, IDictionary<string, string> metadata)
        {
            string expected;
            if (metadata.TryGetValue("compat." + name, out expected))
                return String.Equals(Hashing.Sha256(file), expected, StringComparison.OrdinalIgnoreCase);
            // Pre-context installers recorded no per-file hashes. Their matching old EXE must still verify the view.
            using (var input = File.OpenRead(file)) {
                return input.Length > 22 && input.ReadByte() == 0x50 && input.ReadByte() == 0x4b
                    && input.ReadByte() == 0x03 && input.ReadByte() == 0x04;
            }
        }

        private static void PublishLegacyView(string home, IDictionary<string, string> ui, IDictionary<string, string> ysm)
        {
            string root = LegacyView(home, ui, ysm);
            string identityFile = System.IO.Path.Combine(root, "view.sha256");
            string identity = DependencyIdentity(ui, ysm) + "\n";
            if (File.Exists(identityFile) && File.ReadAllText(identityFile) != identity)
                throw new InvalidDataException("Legacy view identity collision");
            PublishExpected(identityFile, Hashing.Sha256(Encoding.UTF8.GetBytes(identity)),
                temporary => File.WriteAllText(temporary, identity, new UTF8Encoding(false)));
            string uiHash = RequiredHash(ui, "sha256");
            string relative = uiHash + "/moons-ui-runtime.jar";
            string uiSource = System.IO.Path.Combine(home, "libraries", relative.Replace('/', System.IO.Path.DirectorySeparatorChar));
            RequireFile(uiSource, uiHash);
            string targetUi = System.IO.Path.Combine(root, "libraries", relative.Replace('/', System.IO.Path.DirectorySeparatorChar));
            PublishExpected(targetUi, uiHash, temporary => File.Copy(uiSource, temporary, true));
            foreach (var pair in ysm) {
                if (!Regex.IsMatch(pair.Key, "^(libraries|modules)/moons-ysm-[A-Za-z0-9._-]+\\.jar$"))
                    throw new InvalidDataException("Invalid legacy dependency path");
                string source = System.IO.Path.Combine(home, pair.Key.Replace('/', System.IO.Path.DirectorySeparatorChar));
                RequireFile(source, pair.Value);
                string target = System.IO.Path.Combine(root, pair.Key.Replace('/', System.IO.Path.DirectorySeparatorChar));
                PublishExpected(target, pair.Value, temporary => File.Copy(source, temporary, true));
            }
            DirectoryJunction.Ensure(System.IO.Path.Combine(root, "data"), System.IO.Path.Combine(home, "data"));
            string pointer = System.IO.Path.Combine(root, "libraries", "moons-ui-runtime.current");
            string text = relative + "\n";
            PublishExpected(pointer, Hashing.Sha256(Encoding.UTF8.GetBytes(text)), temporary => File.WriteAllText(temporary, text, new UTF8Encoding(false)));
        }

        private static void PublishExpected(string target, string hash, Action<string> write)
        {
            if (File.Exists(target) && String.Equals(Hashing.Sha256(target), hash, StringComparison.OrdinalIgnoreCase)) return;
            // Repair corrupt bytes only with the exact expected content; valid old identities stay untouched.
            Directory.CreateDirectory(System.IO.Path.GetDirectoryName(target));
            StagedFile.Write(target, temporary => { write(temporary); RequireFile(temporary, hash); });
        }

        internal const string UiMetadataResource = "Moons.UiRuntime.properties";
        internal const string YsmMetadataResource = "Moons.Ysm.properties";
        internal const string ReleaseMetadataResource = "Moons.Release.properties";
        internal const string InstallHint = "Run the matching moon-install.exe to install or update dependencies.";

        internal static string ResolveHome()
        {
            string configured = Environment.GetEnvironmentVariable("MOONS_HOME");
            return String.IsNullOrWhiteSpace(configured)
                ? Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), ".moons")
                : Path.GetFullPath(configured.Trim());
        }

        internal static Dictionary<string, string> Metadata(string resource)
        {
            return RuntimeMetadata.Parse(Encoding.UTF8.GetString(ReadResourceBytes(resource)));
        }

        internal static void Verify(string home)
        {
            try { Verify(home, Metadata(UiMetadataResource), Metadata(YsmMetadataResource)); }
            catch (InvalidDataException error)
            {
                string installedPath = Path.Combine(home, "libraries", "moons-dependencies.properties");
                string installed = "unknown";
                if (File.Exists(installedPath))
                {
                    string value;
                    if (RuntimeMetadata.Parse(File.ReadAllText(installedPath)).TryGetValue("dependencies.version", out value))
                        installed = value;
                }
                throw new InvalidDataException(error.Message + "\r\nRequired dependencies: "
                    + Metadata(ReleaseMetadataResource)["dependencies.version"] + "; installed record: " + installed, error);
            }
        }

        internal static string VersionLabel()
        {
            var release = Metadata(ReleaseMetadataResource);
            return "v" + release["client.version"] + "  |  Dependencies " + release["dependencies.version"];
        }

        internal static string VersionDetails()
        {
            return Encoding.UTF8.GetString(ReadResourceBytes(ReleaseMetadataResource)).Trim();
        }

        internal static void RecordInstalledVersion(string home)
        {
            string target = Path.Combine(home, "libraries", "moons-dependencies.properties");
            string content = VersionDetails() + "\n";
            var ysm = Metadata(YsmMetadataResource);
            foreach (string name in YsmPackageNames) content += "compat." + name + "=" + RequiredHash(ysm, name) + "\n";
            if (File.Exists(target) && File.ReadAllText(target) == content) return;
            StagedFile.Write(target, temporary => File.WriteAllText(temporary, content, new UTF8Encoding(false)));
        }

        internal static void Verify(string home, IDictionary<string, string> ui, IDictionary<string, string> ysm)
        {
            string hash = RequiredHash(ui, "sha256");
            string relative = hash + "/moons-ui-runtime.jar";
            string libraries = Path.Combine(home, "libraries");
            RequireFile(Path.Combine(libraries, relative), hash);
            string pointer = Path.Combine(libraries, "moons-ui-runtime.current");
            if (!File.Exists(pointer) || File.ReadAllText(pointer).Trim() != relative)
                throw new InvalidDataException("The installed UI runtime belongs to another build. " + InstallHint);
            foreach (string name in YsmPackageNames)
                RequireFile(Path.Combine(home, name), RequiredHash(ysm, name));
        }

        internal static readonly string[] YsmPackageNames = VersionCatalog.YsmPackageNames();

        internal static void PublishUiRuntime(string home)
        {
            string hash = RequiredHash(Metadata(UiMetadataResource), "sha256");
            string pointer = Path.Combine(home, "libraries", "moons-ui-runtime.current");
            string content = hash + "/moons-ui-runtime.jar\n";
            if (File.Exists(pointer) && File.ReadAllText(pointer) == content) return;
            StagedFile.Write(pointer, temporary => File.WriteAllText(temporary,
                content, new UTF8Encoding(false)));
        }

        private static string RequiredHash(IDictionary<string, string> metadata, string key)
        {
            string hash;
            if (!metadata.TryGetValue(key, out hash) || !Regex.IsMatch(hash, "^[0-9a-fA-F]{64}$"))
                throw new InvalidDataException("Invalid dependency metadata: " + key);
            return hash.ToLowerInvariant();
        }

        private static void RequireFile(string path, string hash)
        {
            if (!File.Exists(path) || !String.Equals(Hashing.Sha256(path), hash, StringComparison.OrdinalIgnoreCase))
                throw new InvalidDataException("Missing or outdated dependency: " + Path.GetFileName(path) + ". " + InstallHint);
        }

        internal static byte[] ReadResourceBytes(string name)
        {
            using (Stream source = Assembly.GetExecutingAssembly().GetManifestResourceStream(name))
            {
                if (source == null) throw new InvalidDataException("Missing embedded resource: " + name);
                using (var memory = new MemoryStream())
                {
                    source.CopyTo(memory);
                    return memory.ToArray();
                }
            }
        }
    }
}
