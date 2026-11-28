using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Reflection;
using Tempos.WindowsLauncher;

internal static class PortableLegacyCleanupVerification
{
    private static void Need(bool value, string message) { if (!value) throw new Exception(message); }

    internal static void Run(string[] args)
    {
        foreach (string mode in args.Length > 1 ? new[] { args[1] } : new[] { "runtime", "cached", "unknown", "changed-copy", "junction", "external-link", "retargeted-link", "markers" }) {
            string fixture = Path.Combine(Path.GetFullPath(args[0]), mode, Guid.NewGuid().ToString("N").Substring(0, 8));
            string app = Path.Combine(fixture, "appdata"), old = Path.Combine(app, ".moons");
            string temp = Path.Combine(fixture, "temp");
            Directory.CreateDirectory(temp);
            Environment.SetEnvironmentVariable("APPDATA", app);
            Environment.SetEnvironmentVariable("TEMP", temp);
            Environment.SetEnvironmentVariable("TMP", temp);
            Put(old, "config/profiles/default.json", "{\"format\":2,\"values\":{\"fixture.current\":false}}");
            Put(old, "bridge.log", "old bridge runtime log");
            Put(old, "native-agent.txt", "old agent diagnostic");
            Put(old, "data/ysm-diagnostic/snapshot.txt", "old model diagnostic");
            Put(old, "config/server/server-example.org-0123456789abcdef.json", "{\"format\":1,\"scope\":\"server:example.org\",\"plugin_xray\":{\"saved\":true}}");
            Put(old, "config/profiles/server/server-example.org-0123456789abcdef.json", "{\"format\":1,\"scope\":\"server:example.org\"}");
            Put(old, "data/ysm/parameters/model.json", "{\"scale\":1.25}");
            Put(old, "data/ysm/parameters/model.json.before-body-size-0123456789abcdef0123456789abcdef.bak", "{\"scale\":1}");
            Put(old, "data/ysm/models/model.ysm", "model asset bytes");
            Put(old, "legacy/0123456789abcdef/view.sha256", new string('a', 64));
            bool linked = mode == "junction" || mode == "external-link" || mode == "retargeted-link";
            if (!linked) foreach (string relative in new[] { "data/ysm/parameters/model.json", "data/ysm/parameters/model.json.before-body-size-0123456789abcdef0123456789abcdef.bak", "data/ysm/models/model.ysm", "data/ysm-diagnostic/snapshot.txt" })
                Put(old, "legacy/0123456789abcdef/" + relative, File.ReadAllText(Path.Combine(old, relative.Replace('/', Path.DirectorySeparatorChar))));
            string link = Path.Combine(old, "legacy", "0123456789abcdef", "data");
            string outside = Path.Combine(fixture, "outside");
            if (linked) {
                Put(outside, "keep.txt", "must remain outside the old root");
                Junction(link, mode == "external-link" ? outside : Path.Combine(old, "data"));
            }
            Put(old, "legacy/0123456789abcdef/libraries/moons-ysm-core.jar", "old runtime bytes");
            if (mode == "unknown") Put(old, "player-notes.txt", "must remain");
            if (mode == "changed-copy") Put(old, "legacy/0123456789abcdef/data/ysm/parameters/model.json", "{\"scale\":2}");
            if (mode == "markers") {
                Put(old, "config/.import-complete-v1", "");
                Put(old, "data/ysm/.import-complete-v1", "");
            }

            string exe = Path.Combine(fixture, "tempos.exe");
            File.Copy(Assembly.GetExecutingAssembly().Location, exe);
            if (mode == "cached") {
                var profiles = new Dictionary<string, object> { { "default", PortableConfig.Json().DeserializeObject("{\"format\":2,\"values\":{\"fixture.current\":true}}") } };
                var extras = new Dictionary<string, object> { { "config/server/server-example.org-0123456789abcdef.json", "{\"fixture.current\":true}" } };
                PortableConfig.Write(exe, PortableConfig.Encode(PortableConfig.NewBundle("default", profiles, extras)));
            }
            string root = Path.Combine(temp, "tempos", Guid.NewGuid().ToString("N"));
            Directory.CreateDirectory(root);
            File.WriteAllText(Path.Combine(root, ".tempos-session"), "fixture");
            try {
                var prepared = PortableConfig.Prepare(root, exe, null);
                Need(prepared.Legacy != null, "Existing EXE cache skipped remaining legacy migration");
                byte[] collected = PortableConfig.Collect(root);
                PortableConfig.Write(exe, collected);
                var saved = PortableConfig.Bundle(PortableConfig.Read(exe));
                var extras = PortableConfig.Object(saved["extras"], "extras");
                Need(extras.ContainsKey("config/server/server-example.org-0123456789abcdef.json"), "Server parameters were not archived");
                Need(extras.ContainsKey("config/profiles/server/server-example.org-0123456789abcdef.json"), "Profile server parameters were not archived");
                Need(extras.ContainsKey("data/ysm/parameters/model.json.before-body-size-0123456789abcdef0123456789abcdef.bak"), "Model parameter backup was not archived");
                if (mode == "cached") {
                    var profiles = PortableConfig.Object(saved["profiles"], "profiles");
                    var profile = PortableConfig.Object(profiles["default"], "default");
                    Need((bool)PortableConfig.Object(profile["values"], "values")["fixture.current"], "Legacy import overwrote current EXE parameters");
                    Need(profiles.Count >= 2, "Different legacy profile was lost");
                    Need(((string)extras["config/server/server-example.org-0123456789abcdef.json"]).Contains("fixture.current"), "Legacy server file overwrote current EXE parameters");
                    bool archived = false;
                    foreach (string key in extras.Keys) if (key.StartsWith("config/legacy/conflicts/")) archived = true;
                    Need(archived, "Different old server parameters were lost");
                }
                if (mode == "retargeted-link") {
                    Directory.Delete(link, false);
                    Junction(link, outside);
                }
                bool protectedData = mode == "unknown" || mode == "changed-copy" || mode == "external-link" || mode == "retargeted-link";
                bool completed = LegacyConfigMigration.Complete(prepared.Legacy.Metadata(), () => false);
                Need(completed != protectedData && Directory.Exists(old) == protectedData,
                    "Incorrect old directory cleanup: " + mode + " completed=" + completed);
                Need(File.ReadAllText(Path.Combine(app, "models", "model.ysm")) == "model asset bytes", "Model not preserved");
                if (linked) Need(File.ReadAllText(Path.Combine(outside, "keep.txt")) == "must remain outside the old root", "Followed junction outside the old root");
            } finally { PortableConfig.DeleteSession(root); }
        }
        Console.WriteLine("LEGACY_CLEANUP_VERIFIED runtime-logs server-parameters model-backup verified-legacy-copies existing-cache-priority unknown-retention changed-copy-retention");
    }

    private static void Junction(string path, string target)
    {
        var start = new ProcessStartInfo("cmd.exe", "/d /c mklink /J " + PortableConfig.Quote(path) + " " + PortableConfig.Quote(target)) {
            UseShellExecute = false, CreateNoWindow = true, RedirectStandardOutput = true, RedirectStandardError = true
        };
        using (var process = Process.Start(start)) {
            process.WaitForExit();
            Need(process.ExitCode == 0, "Could not create isolated junction fixture: " + process.StandardError.ReadToEnd());
        }
    }

    private static void Put(string root, string relative, string text)
    {
        string file = Path.Combine(root, relative.Replace('/', Path.DirectorySeparatorChar));
        Directory.CreateDirectory(Path.GetDirectoryName(file));
        File.WriteAllText(file, text);
    }
}
