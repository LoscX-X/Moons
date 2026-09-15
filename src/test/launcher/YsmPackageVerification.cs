using System;
using System.IO;
using System.IO.Compression;
using System.Text;
using Moons.WindowsLauncher;

internal static class YsmPackageVerification
{
    private static void Main()
    {
        string home = Path.Combine(Path.GetTempPath(), "moons-ysm-package-" + Guid.NewGuid().ToString("N"));
        try
        {
            YsmPackage.Install(home, Package("one", null));
            CheckFiles(home, "one");
            string core = Path.Combine(home, YsmPackage.Files[0]);
            DateTime written = File.GetLastWriteTimeUtc(core);
            YsmPackage.Install(home, Package("one", null));
            Check(File.GetLastWriteTimeUtc(core) == written, "Unchanged libraries must not trigger hot reload");
            string unrelated = Path.Combine(home, "libraries", "moons-ui-runtime.current");
            File.WriteAllText(unrelated, "preserve");
            YsmPackage.Install(home, Package("two", null));
            CheckFiles(home, "two");
            string backupRoot = Path.Combine(home, "cache", "ysm-install");
            Check(!Directory.Exists(backupRoot) || Directory.GetDirectories(backupRoot).Length == 0, "Successful install left backup copies");
            Check(File.ReadAllText(unrelated) == "preserve", "Unrelated library changed");
            // A locked module fails after the libraries were published. Rollback must restore them.
            using (var locked = new FileStream(Path.Combine(home, YsmPackage.Files[3]),
                FileMode.Open, FileAccess.Read, FileShare.Read))
            {
                ExpectFailure(() => YsmPackage.Install(home, Package("three", null)));
            }
            CheckFiles(home, "two");
            ExpectFailure(() => YsmPackage.Install(home, Package("bad", "../escape.jar")));
            ExpectFailure(() => YsmPackage.Install(home, Package("bad", YsmPackage.Files[0])));
            ExpectFailure(() => YsmPackage.Install(home, Package("", null)));
            CheckFiles(home, "two");
            Check(Directory.GetFiles(home, "*.tmp", SearchOption.AllDirectories).Length == 0, "Staging files leaked");
            Console.WriteLine("YSM_PACKAGE_VERIFIED install=6 shared=3 idempotent=true rollback=preserved invalid=unchanged");
        }
        finally { if (Directory.Exists(home)) Directory.Delete(home, true); }
    }


    private static byte[] Package(string version, string extra)
    {
        using (var memory = new MemoryStream())
        {
            using (var archive = new ZipArchive(memory, ZipArchiveMode.Create, true))
            {
                if (version.Length != 0)
                    foreach (string name in YsmPackage.Files)
                        ZipEntries.Write(archive, name, Encoding.UTF8.GetBytes(version + ":" + name));
                if (extra != null) ZipEntries.Write(archive, extra, new byte[] { 1 });
            }
            return memory.ToArray();
        }
    }

    private static void CheckFiles(string home, string version)
    {
        foreach (string name in YsmPackage.Files)
            Check(File.ReadAllText(Path.Combine(home, name)) == version + ":" + name, "Wrong installed version: " + name);
    }

    private static void ExpectFailure(Action action)
    {
        try { action(); }
        catch (InvalidDataException) { return; }
        catch (IOException) { return; }
        throw new Exception("Invalid install succeeded");
    }

    private static void Check(bool value, string message) { if (!value) throw new Exception(message); }
}
