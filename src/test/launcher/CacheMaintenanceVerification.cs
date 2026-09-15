using System;
using System.Diagnostics;
using System.IO;
using Moons.WindowsLauncher;

internal static class CacheMaintenanceVerification
{
    private static readonly Func<bool> Idle = delegate { return false; };
    private static string Hash(int value) { return value.ToString("x64"); }

    private static void Main(string[] arguments)
    {
        string fixture = Path.GetFullPath(Path.Combine(arguments[0], Guid.NewGuid().ToString("N")));
        string home = Path.Combine(fixture, "home");
        string libraries = Path.Combine(home, "libraries");
        string launcher = Path.Combine(home, "cache", "launcher");
        string modules = Path.Combine(home, "cache", "modules");
        string legacy = Path.Combine(fixture, "legacy");
        for (int index = 0; index < 5; index++) Make(Path.Combine(libraries, Hash(index), "moons-ui-runtime.jar"), 2 + index);
        File.WriteAllText(Path.Combine(libraries, "moons-ui-runtime.current"), Hash(4) + "/moons-ui-runtime.jar\n");
        for (int index = 0; index < 16; index++) Make(Path.Combine(launcher, Hash(index), "moons-26.1.jar"), 2);
        for (int index = 0; index < 36; index++) Make(Path.Combine(modules, Hash(index) + ".jar"), 2);
        Make(Path.Combine(legacy, Hash(1), "moons-runtime.jar"), 40);
        Make(Path.Combine(home, "data", "ysm", "models", "user.ysm"), 100);
        Make(Path.Combine(home, "modules", "user.jar"), 100);
        Make(Path.Combine(launcher, Hash(99), "notes.txt"), 100);

        CacheMaintenance.Prune(home, legacy, delegate { return true; });
        Check(Directory.GetDirectories(libraries).Length == 5, "Active JVM caches changed");
        CacheMaintenance.Prune(home, legacy, Idle);
        Check(Directory.GetDirectories(libraries).Length == 2, "UI count not bounded");
        Check(File.Exists(Path.Combine(libraries, Hash(4), "moons-ui-runtime.jar")), "Current UI removed");
        Check(Directory.GetDirectories(launcher).Length == 13, "Launcher limit or unknown-directory preservation failed");
        Check(Directory.GetFiles(modules).Length == 32, "Module cache count not bounded");
        Check(!Directory.Exists(Path.Combine(legacy, Hash(1))), "Expired legacy runtime remained");
        Check(File.Exists(Path.Combine(home, "data", "ysm", "models", "user.ysm")), "User model changed");
        Check(File.Exists(Path.Combine(home, "modules", "user.jar")), "Installed module changed");

        string budget = Path.Combine(fixture, "budget");
        for (int index = 0; index < 3; index++) Make(Path.Combine(budget, Hash(index), "moons-api.jar"), 2);
        CacheMaintenance.Trim(budget, "launcher", 100, 6, 30, null, Idle);
        Check(Directory.GetDirectories(budget).Length == 1, "Byte budget ignored");

        string lockedRoot = Path.Combine(fixture, "locked");
        string lockedFile = Path.Combine(lockedRoot, Hash(1), "moons-api.jar");
        Make(lockedFile, 40);
        using (var held = new FileStream(lockedFile, FileMode.Open, FileAccess.Read, FileShare.Read))
            CacheMaintenance.Trim(lockedRoot, "launcher", 0, 0, 0, null, Idle);
        Check(File.Exists(lockedFile), "Locked cache was deleted");
        CacheMaintenance.Trim(lockedRoot, "launcher", 0, 0, 0, null, Idle);
        Check(!File.Exists(lockedFile), "Unlocked obsolete cache remained");
        string fresh = Path.Combine(lockedRoot, Hash(2), "moons-api.jar");
        Make(fresh, 0);
        CacheMaintenance.Trim(lockedRoot, "launcher", 0, 0, 0, null, Idle);
        Check(File.Exists(fresh), "Recent publication grace ignored");

        string outside = Path.Combine(fixture, "outside");
        Make(Path.Combine(outside, "moons-api.jar"), 40);
        string junction = Path.Combine(lockedRoot, Hash(3));
        var start = new ProcessStartInfo("cmd.exe", "/c mklink /J \"" + junction + "\" \"" + outside + "\"") {
            UseShellExecute = false, CreateNoWindow = true, RedirectStandardOutput = true, RedirectStandardError = true
        };
        using (var process = Process.Start(start))
        {
            process.WaitForExit();
            Check(process.ExitCode == 0, "Could not create the junction safety fixture: " + process.StandardError.ReadToEnd());
        }
        try
        {
            CacheMaintenance.Trim(lockedRoot, "launcher", 0, 0, 0, null, Idle);
            Check(File.Exists(Path.Combine(outside, "moons-api.jar")), "Cleanup followed a directory junction");
        }
        finally { Directory.Delete(junction, false); }
        Console.WriteLine("CACHE_RETENTION_VERIFIED counts, byte budget, age, current UI, active JVM, locked files, grace and junction safety");
    }

    private static void Make(string path, int ageDays)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(path));
        File.WriteAllText(path, "test");
        DateTime when = DateTime.UtcNow.AddDays(-ageDays);
        File.SetLastWriteTimeUtc(path, when);
        Directory.SetLastWriteTimeUtc(Path.GetDirectoryName(path), when);
    }

    private static void Check(bool value, string message) { if (!value) throw new Exception(message); }
}
