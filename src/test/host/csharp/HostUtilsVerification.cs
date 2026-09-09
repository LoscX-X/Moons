using System;
using System.Diagnostics;
using System.IO;
using System.IO.Compression;
using System.Reflection;
using System.Text;
using Moons.Shared;
using Moons.WindowsLauncher;

namespace Moons.Verification
{
    internal static class HostUtilsVerification
    {
        private static void Main(string[] arguments)
        {
            if (arguments.Length == 3)
            {
                VerifyLauncherRuntime(arguments[0], arguments[2], true);
                VerifyLauncherRuntime(arguments[1], arguments[2], false);
                Console.WriteLine("Full and download launcher runtime preparation passed (no game or hardware probes).");
                return;
            }
            byte[] bytes = Encoding.UTF8.GetBytes("abc");
            const string digest = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
            Require(Hashing.Sha256(bytes) == digest, "SHA-256 byte encoding");
            Require(Hashing.Hex(Hashing.Sha256Bytes(bytes)) == digest, "raw digest");

            var metadata = RuntimeMetadata.Parse("# comment\n invalid\n Key = old\r\nKEY = new=tail\npath=C:\\literal\\u1234\n");
            Require(metadata.Count == 2 && metadata["key"] == "new=tail", "metadata key and duplicate rules");
            Require(metadata["PATH"] == @"C:\literal\u1234", "metadata has no escape processing");

            using (MemoryStream archiveBytes = new MemoryStream())
            {
                using (ZipArchive archive = new ZipArchive(archiveBytes, ZipArchiveMode.Create, true))
                {
                    ZipEntries.Write(archive, "source.txt", bytes);
                    archive.CreateEntry("directory/");
                }
                archiveBytes.Position = 0;
                using (ZipArchive source = new ZipArchive(archiveBytes, ZipArchiveMode.Read))
                using (MemoryStream copiedBytes = new MemoryStream())
                {
                    Require(ZipEntries.IsDirectory(source.GetEntry("directory/")), "ZIP directory marker");
                    using (ZipArchive target = new ZipArchive(copiedBytes, ZipArchiveMode.Create, true))
                    {
                        ZipEntries.Copy(source.GetEntry("source.txt"), target, "copy.txt");
                    }
                    copiedBytes.Position = 0;
                    using (ZipArchive target = new ZipArchive(copiedBytes, ZipArchiveMode.Read))
                    {
                        Require(Encoding.UTF8.GetString(ZipEntries.ReadBytes(target.GetEntry("copy.txt"))) == "abc", "ZIP copy contents");
                    }
                }
            }

            string directory = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "io-" + Guid.NewGuid().ToString("N"));
            Directory.CreateDirectory(directory);
            string file = Path.Combine(directory, "value.txt");
            string temporary = file + ".tmp-" + Process.GetCurrentProcess().Id;
            try
            {
                File.WriteAllText(file, "previous");
                StagedFile.Write(file, staged => File.WriteAllBytes(staged, bytes));
                Require(Hashing.Sha256(file) == digest && !File.Exists(temporary), "staged replacement");
                bool failed = false;
                try
                {
                    StagedFile.Write(file, staged =>
                    {
                        File.WriteAllText(staged, "incomplete");
                        throw new IOException("fixture write failure");
                    });
                }
                catch (IOException) { failed = true; }
                Require(failed && Hashing.Sha256(file) == digest && !File.Exists(temporary), "failed write preserves destination and cleans temporary");
            }
            finally
            {
                if (File.Exists(temporary)) File.Delete(temporary);
                if (File.Exists(file)) File.Delete(file);
                Directory.Delete(directory);
            }
            Console.WriteLine("Host utility verification passed (no launcher or hardware probes executed).");
        }

        private static void VerifyLauncherRuntime(string executable, string runtimeJar, bool bundled)
        {
            Assembly launcher = Assembly.LoadFile(Path.GetFullPath(executable));
            Require((Array.IndexOf(launcher.GetManifestResourceNames(), "Moons.UiRuntime.jar") >= 0) == bundled,
                "launcher embedding matches its distribution type");
            MethodInfo prepare = launcher.GetType("Moons.WindowsLauncher.Program", true)
                .GetMethod("EnsureUiRuntime", BindingFlags.Static | BindingFlags.NonPublic);
            string root = Path.GetFullPath(AppDomain.CurrentDomain.BaseDirectory);
            string directory = Path.GetFullPath(Path.Combine(root, "runtime-" + Guid.NewGuid().ToString("N")));
            Require(directory.StartsWith(root.TrimEnd(Path.DirectorySeparatorChar) + Path.DirectorySeparatorChar,
                StringComparison.OrdinalIgnoreCase), "runtime fixture stays in its output directory");
            string[] options = bundled ? new string[0]
                : new[] { "--ui-dependency-url", new Uri(Path.GetFullPath(runtimeJar)).AbsoluteUri };
            try
            {
                string result = (string)prepare.Invoke(null, new object[] {
                    directory, options, null, null, new Func<bool>(() => false)
                });
                Require(Hashing.Sha256(result) == Hashing.Sha256(runtimeJar), "prepared runtime matches the built JAR");
                Require(File.Exists(Path.Combine(directory, "libraries", "moons-ui-runtime.current")),
                    "prepared runtime publishes the cache pointer");
                Type program = launcher.GetType("Moons.WindowsLauncher.Program", true);
                Require((int)program.GetMethod("SelfTestVersionDetection", BindingFlags.Static | BindingFlags.NonPublic)
                    .Invoke(null, null) == 0, "launcher selects only exact supported versions");
                MethodInfo extract = program.GetMethod("ExtractPayload", BindingFlags.Static | BindingFlags.NonPublic);
                string featuresEntry = (string)program.GetField("FeaturesJarEntry", BindingFlags.Static | BindingFlags.NonPublic)
                    .GetRawConstantValue();
                string[] keys = { "26.1", "26.2", "26.3" };
                string[] versions = { "26.1.2", "26.2", "26.3-pre-3" };
                for (int index = 0; index < keys.Length; index++)
                {
                    string payload = (string)extract.Invoke(null, new object[] { directory, keys[index] });
                    string builtPayload = Path.Combine(Path.GetDirectoryName(Path.GetDirectoryName(Path.GetFullPath(executable))),
                        "universal", keys[index], "moons.jar");
                    VerifyPayloadEntries(builtPayload, payload, featuresEntry);
                    using (ZipArchive archive = ZipFile.OpenRead(payload))
                    using (MemoryStream featureBytes = new MemoryStream(ZipEntries.ReadBytes(archive.GetEntry(featuresEntry))))
                    using (ZipArchive features = new ZipArchive(featureBytes, ZipArchiveMode.Read))
                    {
                        var module = RuntimeMetadata.Parse(Encoding.UTF8.GetString(
                            ZipEntries.ReadBytes(features.GetEntry("META-INF/moons-module.properties"))));
                        Require(module["minecraft"] == versions[index], "restored payload has its own version metadata");
                        Require(features.GetEntry("assets/moons/font/minecraft-ascii.ttf") != null,
                            "each payload includes the Minecraft font");
                    }
                }
            }
            finally
            {
                if (Directory.Exists(directory)) Directory.Delete(directory, true);
            }
        }

        private static void VerifyPayloadEntries(string expected, string restored, string featuresEntry)
        {
            using (ZipArchive original = ZipFile.OpenRead(expected))
            using (ZipArchive actual = ZipFile.OpenRead(restored))
                VerifyArchiveEntries(original, actual, featuresEntry);
        }

        private static void VerifyArchiveEntries(ZipArchive expected, ZipArchive actual, string nestedEntry)
        {
            int expectedCount = 0;
            int actualCount = 0;
            foreach (ZipArchiveEntry entry in actual.Entries)
                if (!ZipEntries.IsDirectory(entry)) actualCount++;
            foreach (ZipArchiveEntry entry in expected.Entries)
            {
                if (ZipEntries.IsDirectory(entry)) continue;
                expectedCount++;
                ZipArchiveEntry restored = actual.GetEntry(entry.FullName);
                Require(restored != null, "packaged payload includes " + entry.FullName);
                byte[] expectedBytes = ZipEntries.ReadBytes(entry);
                byte[] actualBytes = ZipEntries.ReadBytes(restored);
                if (entry.FullName == nestedEntry)
                {
                    using (ZipArchive expectedNested = new ZipArchive(new MemoryStream(expectedBytes), ZipArchiveMode.Read))
                    using (ZipArchive actualNested = new ZipArchive(new MemoryStream(actualBytes), ZipArchiveMode.Read))
                        VerifyArchiveEntries(expectedNested, actualNested, null);
                }
                else
                {
                    Require(Convert.ToBase64String(expectedBytes) == Convert.ToBase64String(actualBytes),
                        "packaged payload matches current build: " + entry.FullName);
                }
            }
            Require(expectedCount == actualCount, "packaged payload entry count matches current build");
        }

        private static void Require(bool condition, string message)
        {
            if (!condition) throw new InvalidOperationException(message);
        }
    }
}
