using System;
using System.ComponentModel;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;

namespace Moons.WindowsLauncher
{
    /** Watches only newly appended records for one bridge attempt. */
    internal static class BridgeAttemptMonitor
    {
        private const uint WaitObject0 = 0x00000000;
        private const uint WaitFailed = 0xFFFFFFFF;

        [DllImport("kernel32.dll", SetLastError = true)]
        private static extern uint WaitForSingleObject(IntPtr handle, uint milliseconds);

        internal static long CaptureOffset(string dataDirectory)
        {
            string log = Path.Combine(dataDirectory, "bridge-dll.log");
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

        internal static void WaitForReady(
            string dataDirectory,
            string attempt,
            IntPtr processHandle,
            long initialOffset,
            Func<bool> cancelled)
        {
            string log = Path.Combine(dataDirectory, "bridge-dll.log");
            string marker = "[" + attempt + "]";
            DateTime deadline = DateTime.UtcNow.AddSeconds(20);
            long offset = Math.Max(0L, initialOffset);
            string pending = String.Empty;

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

                string appended;
                if (TryReadAppended(log, ref offset, out appended)
                    && appended.Length > 0)
                {
                    pending += appended;
                    int newline;
                    while ((newline = pending.IndexOf('\n')) >= 0)
                    {
                        string line = pending.Substring(0, newline).TrimEnd('\r');
                        pending = pending.Substring(newline + 1);
                        if (line.IndexOf(marker, StringComparison.Ordinal) < 0)
                        {
                            continue;
                        }
                        ThrowIfFailure(line);
                        if (line.IndexOf("initial retransformation complete",
                            StringComparison.Ordinal) >= 0)
                        {
                            return;
                        }
                    }
                }
                Thread.Sleep(75);
            }

            throw new TimeoutException(
                "The bridge loaded, but Runtime startup and initial retransformation "
                + "did not complete. Check " + log);
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
