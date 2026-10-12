using System;
using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Threading;
using System.Windows.Forms;
using Microsoft.Win32.SafeHandles;

namespace Moons.WindowsLauncher
{
    // One outstanding UI frame, even when the UI thread is busy. WM_TIMER cannot
    // reliably pace a high-refresh display; a waitable timer wakes only this worker.
    internal sealed class ThemeAnimationClock : IDisposable
    {
        private readonly Form owner;
        private readonly Action<double> render;
        private readonly Stopwatch clock = new Stopwatch();
        private readonly ManualResetEvent stop = new ManualResetEvent(false);
        private Thread thread;
        private int generation, pendingGeneration;
        private double lastFrameSeconds;
        private string display;
        private int refreshRate;
        private bool disposed;

        // Native DEVMODEW layout; only the size and current frequency are needed.
        [StructLayout(LayoutKind.Explicit, Size = 220)]
        private struct DisplayMode
        {
            [FieldOffset(68)] internal ushort Size;
            [FieldOffset(184)] internal uint Frequency;
        }

        [DllImport("user32.dll", CharSet = CharSet.Unicode)]
        private static extern bool EnumDisplaySettings(string device, int mode, ref DisplayMode settings);
        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        private static extern SafeWaitHandle CreateWaitableTimerEx(IntPtr attributes, string name, uint flags, uint access);
        [DllImport("kernel32.dll", SetLastError = true)]
        private static extern bool SetWaitableTimer(SafeWaitHandle timer, ref long dueTime, int period,
            IntPtr callback, IntPtr argument, bool resume);
        [DllImport("kernel32.dll", SetLastError = true)]
        private static extern uint WaitForMultipleObjects(uint count, IntPtr[] handles, bool all, uint milliseconds);
        [DllImport("winmm.dll")]
        private static extern uint timeBeginPeriod(uint period);
        [DllImport("winmm.dll")]
        private static extern uint timeEndPeriod(uint period);

        internal ThemeAnimationClock(Form owner, Action<double> render)
        {
            this.owner = owner;
            this.render = render;
        }

        internal double Seconds { get { return clock.Elapsed.TotalSeconds; } }

        internal void Pause() { if (!disposed) Stop(); }

        internal void UpdateState()
        {
            if (disposed) return;
            if (!owner.IsHandleCreated || !owner.Visible || owner.WindowState == FormWindowState.Minimized)
            {
                Stop();
                return;
            }
            string device = Screen.FromHandle(owner.Handle).DeviceName;
            DisplayMode mode = new DisplayMode();
            mode.Size = 220;
            int rate = EnumDisplaySettings(device, -1, ref mode) && mode.Frequency >= 30 && mode.Frequency <= 1000
                ? (int)mode.Frequency : 60;
            if (thread != null && device == display && rate == refreshRate) return;
            Stop();
            display = device;
            refreshRate = rate;
            stop.Reset();
            clock.Start();
            lastFrameSeconds = Seconds;
            int activeGeneration = Interlocked.Increment(ref generation);
            thread = new Thread(delegate() { Run(activeGeneration, rate); });
            thread.IsBackground = true;
            thread.Name = "Moons UI animation";
            thread.Start();
        }

        private void Run(int activeGeneration, int rate)
        {
            bool resolution = false;
            // Windows 10 1803+: high resolution without a process-wide timer change.
            SafeWaitHandle timer = CreateWaitableTimerEx(IntPtr.Zero, null, 2, 0x1F0003);
            if (timer.IsInvalid)
            {
                timer.Dispose();
                timer = CreateWaitableTimerEx(IntPtr.Zero, null, 0, 0x1F0003);
                resolution = timeBeginPeriod(1) == 0;
            }
            try
            {
                double period = 1.0 / rate;
                double nextFrame = Seconds + period;
                if (timer.IsInvalid) return;
                IntPtr[] handles = { stop.SafeWaitHandle.DangerousGetHandle(), timer.DangerousGetHandle() };
                while (!stop.WaitOne(0))
                {
                    long due = -Math.Max(1, (long)((nextFrame - Seconds) * 10000000));
                    if (!SetWaitableTimer(timer, ref due, 0, IntPtr.Zero, IntPtr.Zero, false)) return;
                    if (WaitForMultipleObjects(2, handles, false, UInt32.MaxValue) != 1) return;
                    QueueFrame(activeGeneration);
                    nextFrame += period;
                    double now = Seconds;
                    if (nextFrame <= now) nextFrame = now + period;
                }
            }
            finally
            {
                timer.Dispose();
                if (resolution) timeEndPeriod(1);
            }
        }

        private void QueueFrame(int activeGeneration)
        {
            if (activeGeneration != Volatile.Read(ref generation)
                || Interlocked.CompareExchange(ref pendingGeneration, activeGeneration, 0) != 0) return;
            try
            {
                owner.BeginInvoke((MethodInvoker)delegate {
                    try
                    {
                        if (disposed || activeGeneration != Volatile.Read(ref generation)) return;
                        double now = Seconds;
                        double delta = Math.Min(.25, Math.Max(0, now - lastFrameSeconds));
                        lastFrameSeconds = now;
                        render(delta);
                    }
                    finally { Interlocked.CompareExchange(ref pendingGeneration, 0, activeGeneration); }
                });
            }
            catch (InvalidOperationException)
            {
                Interlocked.CompareExchange(ref pendingGeneration, 0, activeGeneration);
            }
        }

        private void Stop()
        {
            Interlocked.Increment(ref generation);
            stop.Set();
            if (thread != null) thread.Join();
            thread = null;
            clock.Stop();
            Interlocked.Exchange(ref pendingGeneration, 0);
        }

        public void Dispose()
        {
            if (disposed) return;
            disposed = true;
            Stop();
            stop.Dispose();
        }
    }
}
