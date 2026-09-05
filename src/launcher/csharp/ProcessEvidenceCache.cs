using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Management;
using System.Runtime.InteropServices;

namespace Moons.WindowsLauncher
{
    /** Caches expensive WMI evidence for the lifetime of one exact PID. */
    internal static class ProcessEvidenceCache
    {
        private const int MaximumEntries = 128;
        private static readonly TimeSpan EmptyResultLifetime = TimeSpan.FromSeconds(2);
        private static readonly object Gate = new object();
        private static readonly Dictionary<int, Entry> Entries =
            new Dictionary<int, Entry>();

        internal static string CommandLine(int processId)
        {
            long startedTicks;
            if (!TryGetStartTicks(processId, out startedTicks))
            {
                return QueryCommandLine(processId);
            }

            lock (Gate)
            {
                Entry cached;
                if (Entries.TryGetValue(processId, out cached)
                    && cached.StartedTicks == startedTicks
                    && (cached.CommandLine.Length > 0
                        || DateTime.UtcNow - cached.CachedAt < EmptyResultLifetime))
                {
                    return cached.CommandLine;
                }
            }

            string commandLine = QueryCommandLine(processId);
            lock (Gate)
            {
                if (Entries.Count >= MaximumEntries)
                {
                    Entries.Clear();
                }
                Entries[processId] = new Entry(
                    startedTicks, commandLine, DateTime.UtcNow);
            }
            return commandLine;
        }

        private static bool TryGetStartTicks(int processId, out long startedTicks)
        {
            try
            {
                using (Process process = Process.GetProcessById(processId))
                {
                    if (process.HasExited)
                    {
                        startedTicks = 0L;
                        return false;
                    }
                    startedTicks = process.StartTime.ToUniversalTime().Ticks;
                    return true;
                }
            }
            catch (ArgumentException)
            {
            }
            catch (InvalidOperationException)
            {
            }
            catch (System.ComponentModel.Win32Exception)
            {
            }
            startedTicks = 0L;
            return false;
        }

        private static string QueryCommandLine(int processId)
        {
            try
            {
                using (ManagementObjectSearcher searcher = new ManagementObjectSearcher(
                    "SELECT CommandLine FROM Win32_Process WHERE ProcessId=" + processId))
                {
                    foreach (ManagementObject process in searcher.Get())
                    {
                        using (process)
                        {
                            object value = process["CommandLine"];
                            return value == null ? String.Empty : value.ToString();
                        }
                    }
                }
            }
            catch (ManagementException)
            {
            }
            catch (UnauthorizedAccessException)
            {
            }
            catch (COMException)
            {
            }
            return String.Empty;
        }

        private sealed class Entry
        {
            internal readonly long StartedTicks;
            internal readonly string CommandLine;
            internal readonly DateTime CachedAt;

            internal Entry(long startedTicks, string commandLine, DateTime cachedAt)
            {
                StartedTicks = startedTicks;
                CommandLine = commandLine ?? String.Empty;
                CachedAt = cachedAt;
            }
        }
    }
}
