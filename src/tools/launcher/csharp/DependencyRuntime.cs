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

        internal static readonly string[] YsmPackageNames = {
            "libraries/moons-ysm-core.jar", "libraries/moons-ysm-codecs.jar",
            "libraries/moons-ysm-images.jar", "modules/moons-ysm-26.1.2.jar",
            "modules/moons-ysm-26.2.jar", "modules/moons-ysm-26.3.jar"
        };

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
