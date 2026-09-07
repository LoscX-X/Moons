using System;
using System.Collections.Generic;

namespace Moons.WindowsLauncher
{
    /// <summary>Reads the runtime bundle's simple, case-insensitive key=value metadata.</summary>
    internal static class RuntimeMetadata
    {
        // This format deliberately has no Java Properties escape processing.
        internal static Dictionary<string, string> Parse(string text)
        {
            Dictionary<string, string> result =
                new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
            foreach (string rawLine in text.Split(new[] { '\r', '\n' },
                StringSplitOptions.RemoveEmptyEntries))
            {
                string line = rawLine.Trim();
                if (line.Length == 0 || line.StartsWith("#", StringComparison.Ordinal))
                {
                    continue;
                }
                int separator = line.IndexOf('=');
                if (separator <= 0) continue;
                result[line.Substring(0, separator).Trim()] =
                    line.Substring(separator + 1).Trim();
            }
            return result;
        }
    }
}
