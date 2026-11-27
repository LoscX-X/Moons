using System;
using System.Collections.Generic;
using System.IO;
using System.Reflection;
using System.Text;

namespace Tempos.WindowsLauncher
{
    /// <summary>Embedded package metadata and the dependency context for one temporary session.</summary>
    internal static class DependencyRuntime
    {
        internal sealed class Context
        {
            internal readonly string Path, Hash;
            internal Context(string path, string hash) { Path = path; Hash = hash; }
        }

        internal const string UiMetadataResource = "Tempos.UiRuntime.properties";
        internal const string YsmMetadataResource = "Tempos.Ysm.properties";
        internal const string ReleaseMetadataResource = "Tempos.Release.properties";

        internal static string[] RequiredYsm(SupportProfile profile)
        {
            return new[] { "libraries/tempos-ysm-core.jar", "libraries/tempos-ysm-codecs.jar",
                "libraries/tempos-ysm-images.jar", profile.YsmModuleName };
        }

        internal static string ResolveHome()
        {
            string configured = Environment.GetEnvironmentVariable("TEMPOS_HOME");
            return String.IsNullOrWhiteSpace(configured)
                ? Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), ".tempos")
                : Path.GetFullPath(configured.Trim());
        }

        internal static Dictionary<string, string> Metadata(string resource)
        {
            return RuntimeMetadata.Parse(Encoding.UTF8.GetString(ReadResourceBytes(resource)));
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
