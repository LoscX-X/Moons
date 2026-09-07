using System;
using System.Collections.Generic;
using System.Management;
using System.Text;

namespace Moons.Shared
{
    internal static class HardwareIdGenerator
    {
        internal static string Generate()
        {
            List<string> values = new List<string>();
            AddWmi(values, "Win32_ComputerSystemProduct", "UUID");
            AddWmi(values, "Win32_Processor", "ProcessorId");
            AddWmi(values, "Win32_BaseBoard", "SerialNumber");
            AddWmi(values, "Win32_BIOS", "SerialNumber");
            Add(values, "processor", Environment.GetEnvironmentVariable("PROCESSOR_IDENTIFIER"));
            Add(values, "architecture", Environment.GetEnvironmentVariable("PROCESSOR_ARCHITECTURE"));
            Add(values, "processors", Environment.ProcessorCount.ToString());
            if (values.Count == 0)
            {
                throw new InvalidOperationException("No stable hardware identifiers were available.");
            }

            values.Sort(StringComparer.Ordinal);
            byte[] digest = Hashing.Sha256Bytes(Encoding.UTF8.GetBytes(String.Join("\n", values)));

            StringBuilder result = new StringBuilder("MOONS");
            for (int group = 0; group < 6; group++)
            {
                result.Append('-');
                result.Append(digest[group * 2].ToString("X2"));
                result.Append(digest[group * 2 + 1].ToString("X2"));
            }
            return result.ToString();
        }

        private static void AddWmi(List<string> values, string className, string property)
        {
            try
            {
                using (ManagementObjectSearcher searcher = new ManagementObjectSearcher(
                    "SELECT " + property + " FROM " + className))
                using (ManagementObjectCollection results = searcher.Get())
                {
                    foreach (ManagementObject item in results)
                    {
                        Add(values, className + "." + property,
                            Convert.ToString(item[property], System.Globalization.CultureInfo.InvariantCulture));
                    }
                }
            }
            catch
            {
                // Restricted machines can still use the remaining stable sources.
            }
        }

        private static void Add(List<string> values, string name, string value)
        {
            if (String.IsNullOrWhiteSpace(value)) return;
            string normalized = value.Trim().ToLowerInvariant();
            if (normalized == "unknown" || normalized == "none"
                || normalized == "to be filled by o.e.m.") return;
            values.Add(name.ToLowerInvariant() + "=" + normalized);
        }
    }
}
