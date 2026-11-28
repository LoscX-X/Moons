using System;
using System.Diagnostics;
using System.IO;
using System.Reflection;
using System.Text;
using System.Threading;
using Tempos.WindowsLauncher;

/// <summary>Exercises the production archive and helper using isolated files and real PE processes.</summary>
internal static class PortableConfigVerification
{
    private static int Main(string[] args)
    {
        try {
            if (args.Length > 0 && args[0].StartsWith("--", StringComparison.Ordinal)) {
                PortableImageVerification.Run(args);
                return 0;
            }
            if (args.Length != 1) throw new ArgumentException("Expected a fixture parent directory.");
            string fixture = Path.Combine(Path.GetFullPath(args[0]), Guid.NewGuid().ToString("N"));
            Directory.CreateDirectory(fixture);
            string temporary = Path.Combine(fixture, "temp");
            Directory.CreateDirectory(temporary);
            Environment.SetEnvironmentVariable("TEMP", temporary);
            Environment.SetEnvironmentVariable("TMP", temporary);

            PortableImageVerification.Run(new[] { "--tests", Path.Combine(fixture, "image") });
            PortableBundleVerification.Run(new[] { Path.Combine(fixture, "bundle-import.json") });
            PortableImportVerification.Run(Path.Combine(fixture, "import"));
            PortableRecoveryVerification.Run(new[] { Path.Combine(fixture, "recovery") });
            PortableLegacyPropertiesVerification.Run(new[] { Path.Combine(fixture, "properties", "appdata") });
            PortableLegacyCleanupVerification.Run(new[] { Path.Combine(Path.GetDirectoryName(Assembly.GetExecutingAssembly().Location), "lc") });
            VerifyFinalizer(fixture);
            Console.WriteLine("PORTABLE_PARAMETERS_VERIFIED " + fixture);
            return 0;
        } catch (Exception failure) {
            Console.Error.WriteLine(failure);
            return 1;
        }
    }

    private static void VerifyFinalizer(string fixture)
    {
        string directory = Path.Combine(fixture, "lifecycle");
        Directory.CreateDirectory(directory);
        string executable = Path.Combine(directory, "tempos.exe");
        File.Copy(Assembly.GetExecutingAssembly().Location, executable);
        var start = new ProcessStartInfo(executable, "--session " + PortableConfig.Quote(directory)) {
            UseShellExecute = false, CreateNoWindow = true,
            RedirectStandardOutput = true, RedirectStandardError = true
        };
        using (var process = Process.Start(start)) {
            if (!process.WaitForExit(30000)) {
                process.Kill();
                throw new Exception("Fixture launcher did not exit.");
            }
            string output = process.StandardOutput.ReadToEnd(), errors = process.StandardError.ReadToEnd();
            Console.Write(output);
            if (process.ExitCode != 0) throw new Exception("Fixture launcher failed: " + errors);
        }
        var state = PortableConfig.Object(PortableConfig.Json().DeserializeObject(
            File.ReadAllText(Path.Combine(directory, "session-info.json"), Encoding.UTF8)), "fixture state");
        string root = (string)state["root"], helper = (string)state["helper"];
        DateTime deadline = DateTime.UtcNow.AddSeconds(20);
        while ((Directory.Exists(root) || Directory.Exists(helper)) && DateTime.UtcNow < deadline)
            Thread.Sleep(100);
        if (Directory.Exists(root) || Directory.Exists(helper))
            throw new Exception("Finalizer left its session or helper directory.");
        PortableImageVerification.Run(new[] { "--inspect", executable });
        Console.WriteLine("REAL_EXE_FINALIZER_VERIFIED running-image game-lifetime changed-profile EXE-writeback session-cleanup helper-self-cleanup");
    }

}
