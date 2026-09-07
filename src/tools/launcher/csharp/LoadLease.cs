using System;
using System.Threading;

namespace Moons.WindowsLauncher
{
    /** Serializes all launcher instances that target the same JVM process. */
    internal sealed class LoadLease : IDisposable
    {
        private readonly Mutex mutex;
        private bool owned;

        private LoadLease(Mutex mutex, bool owned)
        {
            this.mutex = mutex;
            this.owned = owned;
        }

        internal static LoadLease Acquire(int processId, Func<bool> cancelled)
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
                            return new LoadLease(mutex, true);
                        }
                    }
                    catch (AbandonedMutexException)
                    {
                        return new LoadLease(mutex, true);
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

        public void Dispose()
        {
            if (owned)
            {
                owned = false;
                mutex.ReleaseMutex();
            }
            mutex.Dispose();
        }
    }
}
