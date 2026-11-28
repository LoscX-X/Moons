using System;
using System.Collections.Generic;
using System.IO;
using System.Reflection;
using System.Text;
using Tempos.WindowsLauncher;

internal static class PortableImportVerification
{
    private static int Main(string[] args)
    {
        try { Run(args[0]); return 0; }
        catch (Exception error) { Console.Error.WriteLine(error); return 1; }
    }

    internal static void Run(string directory)
    {
        directory = Path.GetFullPath(directory);
        Directory.CreateDirectory(directory);
        string temporary = Path.Combine(directory, "temp");
        Directory.CreateDirectory(temporary);
        Environment.SetEnvironmentVariable("TEMP", temporary);
        Environment.SetEnvironmentVariable("TMP", temporary);
        byte[] parameters = Parameters();
        string source = Path.Combine(directory, "old-tempos.exe");
        File.Copy(Assembly.GetExecutingAssembly().Location, source, true);
        bool rejected = false;
        try { PortableConfig.ReadImport(source); } catch (InvalidDataException) { rejected = true; }
        if (!rejected) throw new Exception("A clean EXE was accepted as a saved configuration.");
        PortableConfig.Write(source, parameters);
        string original = PortableConfig.FileDigest(source);
        foreach (string input in new[] { source, Path.Combine(directory, "parameters.json") }) {
            if (input.EndsWith(".json")) File.WriteAllBytes(input, parameters);
            string root = Path.Combine(Path.GetTempPath(), "tempos", Guid.NewGuid().ToString("N"));
            Directory.CreateDirectory(root);
            File.WriteAllText(Path.Combine(root, ".tempos-session"), "fixture");
            try {
                var ready = PortableConfig.Prepare(root, Assembly.GetExecutingAssembly().Location, input);
                if (ready.Selected != "我的参数" || PortableConfig.Digest(PortableConfig.Collect(root)) != PortableConfig.Digest(parameters))
                    throw new Exception("Imported configuration lost profiles, selection, values, or model parameters.");
            } finally { PortableConfig.DeleteSession(root); }
        }
        byte[] damaged = File.ReadAllBytes(source);
        damaged[damaged.Length - 25] ^= 1;
        string corrupt = Path.Combine(directory, "corrupt.exe");
        File.WriteAllBytes(corrupt, damaged);
        rejected = false;
        try { PortableConfig.ReadImport(corrupt); } catch (InvalidDataException) { rejected = true; }
        if (!rejected) throw new Exception("A damaged EXE parameter cache was accepted.");
        if (PortableConfig.FileDigest(source) != original) throw new Exception("Import changed the source EXE.");
        Console.WriteLine("PORTABLE_IMPORT_VERIFIED old-EXE JSON all-profiles Unicode selection extras source-unchanged no-cache-rejected corruption-rejected");
    }

    private static byte[] Parameters()
    {
        var profile = new Dictionary<string, object> { { "format", 2 },
            { "values", new Dictionary<string, object> { { "sprintreset.interval", 73 } } } };
        return PortableConfig.Encode(PortableConfig.NewBundle("我的参数",
            new Dictionary<string, object> { { "default", profile }, { "我的参数", profile } },
            new Dictionary<string, object> { { "data/ysm/parameters/1234.json", "{\"scale\":1.25}" } }));
    }

}
