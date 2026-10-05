using System;
using System.Collections.Generic;
using System.IO;
using System.Reflection;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;
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
            string selected = SelectedRoot(home, profile, ui, ysm);
            foreach (string candidate in new[] { selected, AlternateRoot(home, profile, selected),
                System.IO.Path.Combine(home, "libraries", "latest", profile.GameId, SelectedIdentity(profile, ui, ysm)),
                System.IO.Path.Combine(home, "libraries", "legacy", profile.GameId, SelectedIdentity(profile, ui, ysm)) }) {
                if (File.Exists(System.IO.Path.Combine(candidate, "contexts", profile.ProfileId + ".properties"))) {
                    selected = candidate; break;
                }
            }
            string selectedManifest = System.IO.Path.Combine(selected, "contexts", profile.ProfileId + ".properties");
            if (File.Exists(selectedManifest)) {
                string selectedContent = SelectedContent(home, profile, ui, ysm);
                string selectedHash = Hashing.Sha256(Encoding.UTF8.GetBytes(selectedContent));
                RequireFile(selectedManifest, selectedHash);
                VerifySelected(selected, profile, ui, ysm);
                return new Context(selectedManifest, selectedHash);
            }
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

        internal static string[] RequiredYsm(SupportProfile profile)
        {
            return new[] { "libraries/moons-ysm-core.jar", "libraries/moons-ysm-codecs.jar", "libraries/moons-ysm-images.jar", profile.YsmModuleName };
        }

        internal static string SelectedRoot(string home, SupportProfile profile,
            IDictionary<string, string> ui, IDictionary<string, string> ysm)
        {
            if (profile == null || !Regex.IsMatch(profile.GameId, "^[0-9]+(?:\\.[0-9]+)+(?:-(?:snapshot|pre|rc)-[0-9]+)?$"))
                throw new InvalidDataException("Invalid dependency profile.");
            string identity = SelectedIdentity(profile, ui, ysm);
            string area = (profile.Status == "active" ? "latest/" : "legacy/") + profile.GameId;
            return System.IO.Path.Combine(System.IO.Path.GetFullPath(home), "libraries", area.Replace('/', System.IO.Path.DirectorySeparatorChar),
                identity.Substring(0, 16));
        }

        private static string SelectedIdentity(SupportProfile profile, IDictionary<string, string> ui, IDictionary<string, string> ysm)
        {
            var identity = new StringBuilder("layout=3\nprofile=" + profile.GameId + "\njava.minimum=" + profile.JavaVersion + "\nui=" + RequiredHash(ui, "sha256") + "\n");
            foreach (string name in RequiredYsm(profile)) identity.Append(name).Append('=').Append(RequiredHash(ysm, name)).Append('\n');
            return Hashing.Sha256(Encoding.UTF8.GetBytes(identity.ToString()));
        }

        private static string SelectedContent(string home, SupportProfile profile,
            IDictionary<string, string> ui, IDictionary<string, string> ysm)
        {
            var content = new StringBuilder("format=3\nprofile=" + profile.GameId + "\njava.minimum=" + profile.JavaVersion + "\n");
            content.Append("identity=").Append(SelectedIdentity(profile, ui, ysm)).Append('\n');
            // Relative to this immutable package, so retirement never rewrites the manifest/hash.
            content.Append("root=.\nui=ui/moons-ui-runtime.jar\n");
            content.Append("ui.sha256=").Append(RequiredHash(ui, "sha256")).Append('\n');
            content.Append("module=").Append(profile.YsmModuleName).Append('\n');
            foreach (string name in RequiredYsm(profile)) content.Append(name).Append(".sha256=").Append(RequiredHash(ysm, name)).Append('\n');
            return content.ToString();
        }

        private static string AlternateRoot(string home, SupportProfile profile, string root)
        {
            string area = profile.Status == "active" ? "legacy" : "latest";
            return System.IO.Path.Combine(home, "libraries", area, profile.GameId, System.IO.Path.GetFileName(root));
        }

        internal static bool Retire(string home, string version, Func<bool> busy = null)
        {
            if (busy == null) busy = CacheMaintenance.JavaRunning;
            home = System.IO.Path.GetFullPath(home);
            if (!Regex.IsMatch(version ?? "", "^[0-9]+(?:\\.[0-9]+)+(?:-(?:snapshot|pre|rc)-[0-9]+)?$"))
                throw new ArgumentException("Invalid Minecraft version.");
            string identity = Hashing.Sha256(Encoding.UTF8.GetBytes(home.TrimEnd(System.IO.Path.DirectorySeparatorChar).ToUpperInvariant()));
            using (var mutex = new Mutex(false, "Local\\Moons.DependencyInstall." + identity)) {
                bool acquired = false;
                try {
                    try { acquired = mutex.WaitOne(0); } catch (AbandonedMutexException) { acquired = true; }
                    if (!acquired || busy()) return false;
                    string source = System.IO.Path.Combine(home, "libraries", "latest", version);
                    string target = System.IO.Path.Combine(home, "libraries", "legacy", version);
                    if (!CacheMaintenance.Safe(home, source) || !CacheMaintenance.Safe(home, target))
                        throw new IOException("Retirement path contains a directory link.");
                    if (!Directory.Exists(source)) return Directory.Exists(target);
                    if (Directory.Exists(target)) throw new IOException("Legacy version already exists; preserve both packages and resolve the conflict first.");
                    var pending = new Stack<string>(); pending.Push(source);
                    while (pending.Count != 0) {
                        string directory = pending.Pop();
                        foreach (string entry in Directory.GetFileSystemEntries(directory)) {
                            if (!CacheMaintenance.Safe(home, entry)) throw new IOException("Retirement source contains a directory link.");
                            if (Directory.Exists(entry)) pending.Push(entry);
                        }
                    }
                    foreach (string package in Directory.GetDirectories(source)) {
                        string[] manifests = Directory.GetFiles(System.IO.Path.Combine(package, "contexts"), "*.properties");
                        if (manifests.Length != 1) throw new InvalidDataException("Incomplete version package.");
                        var metadata = RuntimeMetadata.Parse(File.ReadAllText(manifests[0]));
                        if (metadata["format"] != "3" || metadata["profile"] != version || metadata["root"] != ".")
                            throw new InvalidDataException("Only relocatable version packages can be retired.");
                        RequireFile(System.IO.Path.Combine(package, "ui", "moons-ui-runtime.jar"), metadata["ui.sha256"]);
                        foreach (var pair in metadata) if (pair.Key.EndsWith(".sha256") && pair.Key != "ui.sha256") {
                            string name = pair.Key.Substring(0, pair.Key.Length - 7);
                            if (!Regex.IsMatch(name, "^(libraries|modules)/moons-ysm-[A-Za-z0-9._-]+\\.jar$"))
                                throw new InvalidDataException("Invalid retired dependency path.");
                            RequireFile(System.IO.Path.Combine(package, name), pair.Value);
                        }
                    }
                    Directory.CreateDirectory(System.IO.Path.GetDirectoryName(target));
                    if (busy()) return false;
                    Directory.Move(source, target);
                    return true;
                } finally { if (acquired) mutex.ReleaseMutex(); }
            }
        }

        internal static void VerifySelected(string root, SupportProfile profile,
            IDictionary<string, string> ui, IDictionary<string, string> ysm)
        {
            RequireFile(System.IO.Path.Combine(root, "ui", "moons-ui-runtime.jar"), RequiredHash(ui, "sha256"));
            foreach (string name in RequiredYsm(profile)) RequireFile(System.IO.Path.Combine(root, name), RequiredHash(ysm, name));
        }

        internal static void PublishSelected(string home, SupportProfile profile,
            IDictionary<string, string> ui, IDictionary<string, string> ysm)
        {
            string root = SelectedRoot(home, profile, ui, ysm);
            CheckSelectedIdentity(root, profile, ui, ysm);
            VerifySelected(root, profile, ui, ysm);
            string content = SelectedContent(home, profile, ui, ysm);
            string target = System.IO.Path.Combine(root, "contexts", profile.ProfileId + ".properties");
            PublishExpected(target,
                Hashing.Sha256(Encoding.UTF8.GetBytes(content)), temporary => File.WriteAllText(temporary, content, new UTF8Encoding(false)));
        }

        internal static void CheckSelectedIdentity(string root, SupportProfile profile,
            IDictionary<string, string> ui, IDictionary<string, string> ysm)
        {
            string contexts = System.IO.Path.Combine(root, "contexts");
            if (!Directory.Exists(contexts)) return;
            foreach (string target in Directory.GetFiles(contexts, "*.properties")) {
                string installedIdentity;
                if (RuntimeMetadata.Parse(File.ReadAllText(target)).TryGetValue("identity", out installedIdentity)
                    && installedIdentity != SelectedIdentity(profile, ui, ysm)) throw new InvalidDataException("Dependency package identity collision.");
            }
        }

        internal static void PublishObject(string home, string target, string hash, Action<string> write)
        {
            if (!Regex.IsMatch(hash, "^[0-9a-f]{64}$")) throw new InvalidDataException("Invalid library object identity.");
            string objects = System.IO.Path.Combine(home, "libraries", "objects");
            string source = System.IO.Path.Combine(objects, hash + ".jar");
            if (!CacheMaintenance.Safe(home, target) || !CacheMaintenance.Safe(home, source))
                throw new IOException("Library object path contains a directory link or escapes MOONS_HOME.");
            bool validObject = File.Exists(source) && Hashing.Sha256(source) == hash;
            if (validObject && File.Exists(target) && Hashing.Sha256(target) == hash
                && DirectoryJunction.SameFile(target, source)) return;
            // Check destination locks before repairing an object shared by existing hard links.
            using (var reservation = File.Exists(target)
                ? new FileStream(target, FileMode.Open, FileAccess.ReadWrite, FileShare.Read | FileShare.Delete) : null) {
                if (!validObject) {
                    Directory.CreateDirectory(objects);
                    // A damaged object may share the reserved destination's inode. Replace it
                    // directly; reopening it with File.OpenRead would conflict with our reservation.
                    StagedFile.Write(source, temporary => { write(temporary); RequireFile(temporary, hash); });
                }
                Directory.CreateDirectory(System.IO.Path.GetDirectoryName(target));
                StagedFile.Write(target, temporary => {
                    if (!DirectoryJunction.TryHardLink(temporary, source)) File.Copy(source, temporary, true);
                    RequireFile(temporary, hash);
                });
            }
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
            try { foreach (var profile in VersionCatalog.Profiles) Resolve(home, profile); }
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
            RecordInstalledVersion(home, VersionDetails(), Metadata(YsmMetadataResource));
        }

        internal static void RecordInstalledVersion(string home, string details, IDictionary<string, string> ysm)
        {
            string target = Path.Combine(home, "libraries", "moons-dependencies.properties");
            string content = details.Trim() + "\n";
            foreach (string name in ysm.Keys) content += "compat." + name + "=" + RequiredHash(ysm, name) + "\n";
            Directory.CreateDirectory(System.IO.Path.GetDirectoryName(target));
            if (File.Exists(target) && File.ReadAllText(target) == content) return;
            StagedFile.Write(target, temporary => File.WriteAllText(temporary, content, new UTF8Encoding(false)));
        }

        internal static void PrepareFrozenHome(string home, string root, SupportProfile profile,
            IDictionary<string, string> ui, IDictionary<string, string> ysm)
        {
            string hash = RequiredHash(ui, "sha256");
            byte[] bytes = File.ReadAllBytes(System.IO.Path.Combine(root, "ui", "moons-ui-runtime.jar"));
            PublishObject(home, System.IO.Path.Combine(root, "libraries", hash, "moons-ui-runtime.jar"), hash,
                temporary => File.WriteAllBytes(temporary, bytes));
            string pointer = hash + "/moons-ui-runtime.jar\n";
            PublishExpected(System.IO.Path.Combine(root, "libraries", "moons-ui-runtime.current"),
                Hashing.Sha256(Encoding.UTF8.GetBytes(pointer)), temporary => File.WriteAllText(temporary, pointer, new UTF8Encoding(false)));
            DirectoryJunction.Ensure(System.IO.Path.Combine(root, "data"), System.IO.Path.Combine(home, "data"));
            // Old EXEs resolve through MOONS_HOME. Their cache is rebuildable and separate from installed libraries.
            DirectoryJunction.Ensure(System.IO.Path.Combine(root, "cache"), System.IO.Path.Combine(home, "cache"));
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
            return ReadResourceBytes(Assembly.GetExecutingAssembly(), name);
        }

        internal static byte[] ReadResourceBytes(Assembly assembly, string name)
        {
            using (Stream source = assembly.GetManifestResourceStream(name))
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
