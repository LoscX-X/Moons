using System;
using System.Collections.Generic;
using System.IO;
using System.Text;
using Moons.Shared;
using Moons.WindowsLauncher;

internal static class DependencyContextsVerification
{
    private static void Main(string[] args)
    {
        string home = Path.Combine(Path.GetFullPath(args[0]), "中文 space-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(home);
        var first = Install(home, "A");
        var profile = VersionCatalog.Profiles[0];
        var a = DependencyRuntime.Resolve(home, profile, first.Item1, first.Item2);
        string legacyA = DependencyRuntime.LegacyView(home, first.Item1, first.Item2);
        DependencyRuntime.Verify(legacyA, first.Item1, first.Item2);
        File.WriteAllText(Path.Combine(home, "data", "shared-user-config.txt"), "user setting");
        Check(File.ReadAllText(Path.Combine(legacyA, "data", "shared-user-config.txt")) == "user setting", "Legacy view copied or reset shared user data");
        var oldBytes = File.ReadAllBytes(a.Path);
        var second = Install(home, "B");
        DependencyRuntime.Verify(legacyA, first.Item1, first.Item2);
        File.WriteAllText(Path.Combine(legacyA, "data", "shared-user-config.txt"), "changed in A");
        string legacyB = DependencyRuntime.LegacyView(home, second.Item1, second.Item2);
        Check(File.ReadAllText(Path.Combine(legacyB, "data", "shared-user-config.txt")) == "changed in A", "Views have different user data");
        var b = DependencyRuntime.Resolve(home, profile, second.Item1, second.Item2);
        var again = DependencyRuntime.Resolve(home, profile, first.Item1, first.Item2);
        Check(a.Path == again.Path && a.Hash == again.Hash && a.Path != b.Path, "A -> B -> A context changed");
        Check(Hashing.Sha256(oldBytes) == Hashing.Sha256(a.Path), "Old context was overwritten");
        string rootB = DependencyRuntime.ViewRoot(home, second.Item1, second.Item2);
        string otherAdapter = Path.Combine(rootB, VersionCatalog.Profiles[1].YsmModuleName.Replace('/', Path.DirectorySeparatorChar));
        File.Delete(otherAdapter);
        DependencyRuntime.Resolve(home, profile, second.Item1, second.Item2);
        ExpectFailure(() => DependencyRuntime.Resolve(home, VersionCatalog.Profiles[1], second.Item1, second.Item2));
        string core = Path.Combine(rootB, "libraries", "moons-ysm-core.jar");
        File.WriteAllText(core, "broken");
        ExpectFailure(() => DependencyRuntime.Resolve(home, profile, second.Item1, second.Item2));
        DependencyRuntime.Resolve(home, profile, first.Item1, first.Item2);
        DependencyRuntime.PublishViews(home, second.Item1, second.Item2);
        DependencyRuntime.Resolve(home, profile, second.Item1, second.Item2);
        string firstUi = Path.Combine(home, "libraries", first.Item1["sha256"], "moons-ui-runtime.jar");
        File.SetLastWriteTimeUtc(firstUi, DateTime.UtcNow.AddDays(-100));
        Directory.SetLastWriteTimeUtc(Path.GetDirectoryName(firstUi), DateTime.UtcNow.AddDays(-100));
        File.WriteAllText(Path.Combine(home, "libraries", "moons-ui-runtime.current"), second.Item1["sha256"] + "/moons-ui-runtime.jar\n");
        CacheMaintenance.Prune(home, Path.Combine(home, "legacy-cache"), () => false);
        Check(File.Exists(firstUi), "GC removed UI retained by an older context");
        DependencyRuntime.Resolve(home, profile, first.Item1, first.Item2);
        var selectedA = InstallSelected(home, profile, "selected-A");
        var selectedB = InstallSelected(home, VersionCatalog.Profiles[1], "selected-B");
        var independentA = DependencyRuntime.Resolve(home, profile, selectedA.Item1, selectedA.Item2);
        var independentB = DependencyRuntime.Resolve(home, VersionCatalog.Profiles[1], selectedB.Item1, selectedB.Item2);
        Check(independentA.Path.Contains("libraries\\latest\\" + profile.GameId), "Latest package is not grouped by game version");
        Check(!File.Exists(Path.Combine(DependencyRuntime.SelectedRoot(home, profile, selectedA.Item1, selectedA.Item2), VersionCatalog.Profiles[1].YsmModuleName)), "Unselected adapter installed");
        var legacyProfile = new SupportProfile("1_8_9", "1.8.9", "1.8.9", "legacy", "", "^$", new[] { "1.8.9" }, 8);
        var selectedLegacy = InstallSelected(home, legacyProfile, "java8-legacy");
        var legacyContext = DependencyRuntime.Resolve(home, legacyProfile, selectedLegacy.Item1, selectedLegacy.Item2);
        Check(legacyContext.Path.Contains("libraries\\legacy\\1.8.9\\") && File.ReadAllText(legacyContext.Path).Contains("java.minimum=8"), "Legacy library layout/Java version lost");
        Check(selectedA.Item1["sha256"] != selectedLegacy.Item1["sha256"], "Fixture failed to distinguish incompatible UI dependencies");
        byte[] originalManifest = File.ReadAllBytes(independentA.Path);
        Check(!DependencyRuntime.Retire(home, profile.GameId, () => true), "Busy version was retired");
        Check(DependencyRuntime.Retire(home, profile.GameId, () => false), "Idle version could not be retired");
        var retired = DependencyRuntime.Resolve(home, profile, selectedA.Item1, selectedA.Item2);
        Check(retired.Path.Contains("libraries\\legacy\\" + profile.GameId) && retired.Hash == independentA.Hash
            && Hashing.Sha256(originalManifest) == Hashing.Sha256(retired.Path), "Retirement rewrote dependency manifests");
        DependencyRuntime.Resolve(home, VersionCatalog.Profiles[1], selectedB.Item1, selectedB.Item2);
        string handoff = Path.Combine(args[0], "dependency-fixture.properties");
        File.WriteAllText(handoff, "home=" + home + "\nmanifest=" + retired.Path + "\nhash=" + retired.Hash + "\nprofile=" + profile.GameId + "\n", new UTF8Encoding(false));
        Console.WriteLine("MOONS_DEPENDENCY_CONTEXTS_VERIFIED A-B-A profile-closure corruption-repair old-ui-retention unicode independent-selected legacy-java8 immutable-retirement");
    }

    private static Tuple<Dictionary<string,string>, Dictionary<string,string>> InstallSelected(string home, SupportProfile profile, string version)
    {
        var ui = new Dictionary<string,string>();
        byte[] bytes = Encoding.UTF8.GetBytes("selected UI " + version);
        ui["sha256"] = Hashing.Sha256(bytes);
        var ysm = new Dictionary<string,string>();
        foreach (string name in DependencyRuntime.RequiredYsm(profile)) ysm[name] = Hashing.Sha256(Encoding.UTF8.GetBytes(name + " " + version));
        string root = DependencyRuntime.SelectedRoot(home, profile, ui, ysm);
        DependencyRuntime.PublishObject(home, Path.Combine(root, "ui", "moons-ui-runtime.jar"), ui["sha256"], temporary => File.WriteAllBytes(temporary, bytes));
        foreach (string name in DependencyRuntime.RequiredYsm(profile)) {
            byte[] content = Encoding.UTF8.GetBytes(name + " " + version);
            DependencyRuntime.PublishObject(home, Path.Combine(root, name), ysm[name], temporary => File.WriteAllBytes(temporary, content));
        }
        DependencyRuntime.PublishSelected(home, profile, ui, ysm);
        return Tuple.Create(ui, ysm);
    }

    private static Tuple<Dictionary<string,string>, Dictionary<string,string>> Install(string home, string version)
    {
        var ui = new Dictionary<string,string>();
        byte[] bytes = Encoding.UTF8.GetBytes("ui " + version);
        ui["sha256"] = Hashing.Sha256(bytes);
        string uiPath = Path.Combine(home, "libraries", ui["sha256"], "moons-ui-runtime.jar");
        Directory.CreateDirectory(Path.GetDirectoryName(uiPath)); File.WriteAllBytes(uiPath, bytes);
        var ysm = new Dictionary<string,string>();
        foreach (string name in DependencyRuntime.YsmPackageNames) {
            bytes = Encoding.UTF8.GetBytes(name + " " + version);
            ysm[name] = Hashing.Sha256(bytes);
            string path = Path.Combine(home, name.Replace('/', Path.DirectorySeparatorChar));
            Directory.CreateDirectory(Path.GetDirectoryName(path)); File.WriteAllBytes(path, bytes);
        }
        DependencyRuntime.PublishViews(home, ui, ysm);
        return Tuple.Create(ui, ysm);
    }

    private static void ExpectFailure(Action action)
    {
        try { action(); } catch (InvalidDataException) { return; }
        throw new Exception("Missing or corrupt selected dependency was accepted");
    }
    private static void Check(bool value, string message) { if (!value) throw new Exception(message); }
}
