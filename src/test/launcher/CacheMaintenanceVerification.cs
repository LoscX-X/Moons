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
        Check(CacheMaintenance.IsBuildJvm("java -Xmx1g org.gradle.launcher.daemon.bootstrap.GradleDaemon 9.5.1"), "Gradle blocks idle cleanup");
        Check(CacheMaintenance.IsBuildJvm("java org.jetbrains.kotlin.daemon.KotlinCompileDaemon"), "Kotlin compiler blocks idle cleanup");
        Check(!CacheMaintenance.IsBuildJvm("java net.minecraft.client.main.Main --version 26.2")
            && !CacheMaintenance.IsBuildJvm("java unknown.Main") && !CacheMaintenance.IsBuildJvm(""), "Game or unknown Java lost protection");
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
        Make(Path.Combine(launcher, Hash(96), "moons-26.4-snapshot-1.jar"), 2);
        Make(Path.Combine(launcher, Hash(97), "moons-1.8.9.jar"), 2);
        Make(Path.Combine(modules, "builtin-core-features.jar"), 2);
        Make(Path.Combine(legacy, Hash(1), "moons-runtime.jar"), 40);
        Make(Path.Combine(home, "data", "ysm", "models", "user.ysm"), 100);
        Make(Path.Combine(home, "modules", "user.jar"), 100);
        Make(Path.Combine(launcher, Hash(99), "notes.txt"), 100);

        CacheMaintenance.Prune(home, legacy, delegate { return true; });
        Check(Directory.GetDirectories(libraries).Length == 5, "Active JVM caches changed");
        CacheMaintenance.Prune(home, legacy, Idle);
        Check(Directory.GetDirectories(libraries).Length == 2, "UI count not bounded");
        Check(File.Exists(Path.Combine(libraries, Hash(4), "moons-ui-runtime.jar")), "Current UI removed");
        Check(Directory.GetDirectories(launcher).Length == 1, "Old launcher files retained or unknown-directory preservation failed");
        Check(Directory.GetFiles(modules).Length == 0, "Old module working files retained");
        Check(!Directory.Exists(Path.Combine(legacy, Hash(1))), "Expired legacy runtime remained");
        Check(File.Exists(Path.Combine(home, "data", "ysm", "models", "user.ysm")), "User model changed");
        Check(File.Exists(Path.Combine(home, "modules", "user.jar")), "Installed module changed");

        string latestPayload = Path.Combine(launcher, Hash(101), "moons-1.8.9.jar");
        string obsoletePayload = Path.Combine(launcher, Hash(102), "moons-26.2.jar");
        string latestModule = Path.Combine(modules, Hash(103) + ".jar");
        string obsoleteModule = Path.Combine(modules, Hash(104) + ".jar");
        string latestRuntime = Path.Combine(home, "cache", "runtime", Hash(105), "moons-runtime.jar");
        string obsoleteRuntime = Path.Combine(home, "cache", "runtime", Hash(106), "moons-runtime.jar");
        Make(latestPayload, 30); Make(obsoletePayload, 30);
        Make(latestModule, 10); Make(obsoleteModule, 30);
        Make(latestRuntime, 10); Make(obsoleteRuntime, 30);
        CacheMaintenance.RecordRun(home, "1.8.9", DateTime.UtcNow.AddDays(-15), latestPayload);
        string failedModule = Path.Combine(modules, Hash(107) + ".jar");
        Make(failedModule, 1);
        CacheMaintenance.Prune(home, legacy, Idle);
        Check(File.Exists(latestPayload) && File.Exists(latestModule) && File.Exists(latestRuntime), "Latest legacy run cache lost");
        Check(!File.Exists(obsoletePayload) && !File.Exists(obsoleteModule) && !File.Exists(obsoleteRuntime), "Previous mainline dependencies remained in the latest run cache");
        Check(!File.Exists(failedModule), "Failed later attempt expanded the latest successful cache indefinitely");
        File.WriteAllText(Path.Combine(home, "cache", "last-run.properties"), "format=1\nprofile=1.8.9\nstarted=invalid\n");
        CacheMaintenance.Prune(home, legacy, Idle);
        Check(File.Exists(latestPayload) && File.Exists(latestModule), "Invalid receipt authorized cache deletion");

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
        Console.WriteLine("CACHE_RETENTION_VERIFIED latest-successful-run legacy-independent invalid-receipt snapshot builtin byte-budget current-UI active-JVM locked-files grace junction-safety");
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
