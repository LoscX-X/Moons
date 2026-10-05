using System;
using System.Threading;
using System.ComponentModel;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;
using System.Collections.Generic;

namespace Moons.WindowsLauncher
{
    /** Owns a single target's load mutex, one-shot configuration and startup confirmation. */
    internal sealed class LoadSession : IDisposable
    {
        private readonly Mutex mutex;
        private bool owned;
        private readonly int processId;
        private readonly Func<bool> cancelled;
        private string dataDirectory;
        private readonly List<LogCursor> logs = new List<LogCursor>();
        private readonly List<string> configurations = new List<string>();
        internal readonly string Attempt = Guid.NewGuid().ToString("N");

        private LoadSession(Mutex mutex, int processId, Func<bool> cancelled)
        {
            this.mutex = mutex;
            this.owned = true;
            this.processId = processId;
            this.cancelled = cancelled;
        }

        internal static LoadSession Acquire(int processId, Func<bool> cancelled)
        {
            Mutex mutex = new Mutex(false, "Local\\Moons-Load-" + processId);
            DateTime deadline = DateTime.UtcNow.AddSeconds(5);
            try
            {
                while (DateTime.UtcNow < deadline)
                {
                    if (cancelled())
                    {
                        throw new OperationCanceledException();
                    }
                    try
                    {
                        if (mutex.WaitOne(100))
                        {
                            return new LoadSession(mutex, processId, cancelled);
                        }
                    }
                    catch (AbandonedMutexException)
                    {
                        return new LoadSession(mutex, processId, cancelled);
                    }
                }
                throw new TimeoutException(
                    "Another launcher is already loading into PID " + processId + ".");
            }
            catch
            {
                mutex.Dispose();
                throw;
            }
        }

        internal void Prepare(string directory, string payload, string bootstrapApi,
            string home, string displayName, string hardwareId,
            string dependencyContext = null, string dependencyHash = null) {
            if (!owned) throw new ObjectDisposedException("LoadSession");
            if (dataDirectory != null) throw new InvalidOperationException("Load attempt already prepared.");
            Directory.CreateDirectory(directory);
            Directory.CreateDirectory(home);
            string transport = Path.Combine(directory, "cache", "bridge");
            Directory.CreateDirectory(transport);
            string name = "bridge-" + processId + ".conf";
            string content =
                "payload=" + payload + Environment.NewLine
                + "bootstrap=" + bootstrapApi + Environment.NewLine
                + "home=" + home + Environment.NewLine
                + "name=" + displayName + Environment.NewLine
                + "hwid=" + hardwareId + Environment.NewLine
                + "attempt=" + Attempt
                + (dependencyContext == null ? String.Empty : Environment.NewLine
                    + "dependencies=" + dependencyContext + Environment.NewLine
                    + "dependencies.sha256=" + dependencyHash);
            // The root copy supports a bridge already loaded by an older launcher.
            foreach (string configPath in new[] { Path.Combine(transport, name), Path.Combine(directory, name) }) {
                File.WriteAllText(configPath, content, new UTF8Encoding(false));
                configurations.Add(configPath);
            }
            foreach (string path in new[] { Path.Combine(directory, "logs", "bridge-dll.log"), Path.Combine(directory, "bridge-dll.log") })
                logs.Add(new LogCursor { Path = path, Offset = CaptureOffset(path) });
            dataDirectory = directory;
        }

        public void Dispose()
        {
            if (owned)
            {
                owned = false;
                foreach (string config in configurations) {
                    try {
                        if (File.Exists(config) && File.ReadAllText(config).Contains("attempt=" + Attempt)) File.Delete(config);
                    } catch (IOException) { } catch (UnauthorizedAccessException) { }
                }
                mutex.ReleaseMutex();
            }
            mutex.Dispose();
        }
        private const uint WaitObject0 = 0x00000000;
        private const uint WaitFailed = 0xFFFFFFFF;

        [DllImport("kernel32.dll", SetLastError = true)]
        private static extern uint WaitForSingleObject(IntPtr handle, uint milliseconds);

        private static long CaptureOffset(string log)
        {
            try
            {
                return File.Exists(log) ? new FileInfo(log).Length : 0L;
            }
            catch (IOException)
            {
                return 0L;
            }
            catch (UnauthorizedAccessException)
            {
                return 0L;
            }
        }

        internal void WaitForReady(IntPtr processHandle)
        {
            if (!owned) throw new ObjectDisposedException("LoadSession");
            if (dataDirectory == null) throw new InvalidOperationException("Load attempt is not prepared.");
            string log = Path.Combine(dataDirectory, "logs", "bridge-dll.log");
            string marker = "[" + Attempt + "]";
            DateTime deadline = DateTime.UtcNow.AddSeconds(20);
            bool retransformed = false;
            bool coreReady = false;

            while (DateTime.UtcNow < deadline)
            {
                if (cancelled())
                {
                    throw new OperationCanceledException();
                }
                uint processWait = processHandle == IntPtr.Zero
                    ? 0x00000102 : WaitForSingleObject(processHandle, 0);
                if (processWait == WaitObject0)
                {
                    throw new InvalidOperationException(
                        "The target JVM exited while the JVMTI bridge was starting.");
                }
                if (processWait == WaitFailed)
                {
                    throw new Win32Exception(Marshal.GetLastWin32Error(),
                        "Unable to monitor the target JVM process");
                }

                foreach (var cursor in logs) {
                string appended;
                if (TryReadAppended(cursor.Path, ref cursor.Offset, out appended)
                    && appended.Length > 0)
                {
                    cursor.Pending += appended;
                    int newline;
                    while ((newline = cursor.Pending.IndexOf('\n')) >= 0)
                    {
                        string line = cursor.Pending.Substring(0, newline).TrimEnd('\r');
                        cursor.Pending = cursor.Pending.Substring(newline + 1);
                        if (line.IndexOf(marker, StringComparison.Ordinal) < 0)
                        {
                            continue;
                        }
                        ThrowIfFailure(line);
                        if (line.IndexOf("initial retransformation complete",
                            StringComparison.Ordinal) >= 0)
                        {
                            retransformed = true;
                        }
                        if (line.IndexOf("core ready: READY:", StringComparison.Ordinal) >= 0) coreReady = true;
                        if (retransformed && coreReady) return;
                    }
                }
                }
                Thread.Sleep(75);
            }

            throw new TimeoutException(
                "The bridge loaded, but required hooks, first client tick and core startup "
                + "did not complete. Check " + log);
        }

        private sealed class LogCursor
        {
            internal string Path, Pending = String.Empty;
            internal long Offset;
        }

        private static bool TryReadAppended(
            string path,
            ref long offset,
            out string appended)
        {
            appended = String.Empty;
            try
            {
                if (!File.Exists(path))
                {
                    return false;
                }
                using (FileStream stream = new FileStream(
                    path, FileMode.Open, FileAccess.Read,
                    FileShare.ReadWrite | FileShare.Delete))
                {
                    if (stream.Length < offset)
                    {
                        offset = 0L;
                    }
                    if (stream.Length == offset)
                    {
                        return false;
                    }
                    stream.Position = offset;
                    using (StreamReader reader = new StreamReader(
                        stream, new UTF8Encoding(false), false, 4096, true))
                    {
                        appended = reader.ReadToEnd();
                        offset = stream.Position;
                    }
                    return true;
                }
            }
            catch (IOException)
            {
                return false;
            }
            catch (UnauthorizedAccessException)
            {
                return false;
            }
        }

        private static void ThrowIfFailure(string line)
        {
            if (line.IndexOf("hard failure", StringComparison.OrdinalIgnoreCase) >= 0
                || line.IndexOf("bridge failed", StringComparison.OrdinalIgnoreCase) >= 0
                || line.IndexOf("invalid one-shot config", StringComparison.OrdinalIgnoreCase) >= 0
                || line.IndexOf("startup failed", StringComparison.OrdinalIgnoreCase) >= 0)
            {
                throw new InvalidOperationException(line);
            }
        }
    }
}
