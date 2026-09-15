using System;
using System.IO;
using System.Text;
using System.Threading;
using Moons.WindowsLauncher;

internal static class LoadSessionVerification
{
    private static void Main()
    {
        string root = Path.Combine(Path.GetTempPath(), "moons-load-session-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(root);
        int pid = System.Diagnostics.Process.GetCurrentProcess().Id;
        string log = Path.Combine(root, "bridge-dll.log");
        try
        {
            using (LoadSession session = LoadSession.Acquire(pid, () => false))
            {
                File.WriteAllText(log, "[" + session.Attempt + "] hard failure stale\n");
                session.Prepare(root, "payload.jar", "api.jar", Path.Combine(root, "home"), "fixture", "test");
                string config = File.ReadAllText(Path.Combine(root, "bridge-" + pid + ".conf"));
                Check(config.Contains("attempt=" + session.Attempt) && config.Contains("payload=payload.jar"), "One session owns its config token");
                Expect<InvalidOperationException>(() => session.Prepare(root, "", "", root, "", ""));
                Exception workerFailure = null;
                var worker = new Thread(() => {
                    try
                    {
                        int checks = 0;
                        Expect<OperationCanceledException>(() => {
                            using (LoadSession blocked = LoadSession.Acquire(pid, () => ++checks > 2))
                                throw new Exception("Concurrent load acquired the same target");
                        });
                    }
                    catch (Exception failure) { workerFailure = failure; }
                });
                worker.Start();
                Check(worker.Join(3000), "Concurrent acquisition finishes on cancellation");
                if (workerFailure != null) throw workerFailure;
                File.AppendAllText(log, "[other] hard failure unrelated\n[" + session.Attempt + "] initial retransformation complete\n", new UTF8Encoding(false));
                session.WaitForReady(IntPtr.Zero);
            }
            using (LoadSession session = LoadSession.Acquire(pid, () => false))
            {
                session.Prepare(root, "payload.jar", "api.jar", root, "fixture", "test");
                File.AppendAllText(log, "[" + session.Attempt + "] startup failed\n");
                Expect<InvalidOperationException>(() => session.WaitForReady(IntPtr.Zero));
            }
            bool cancel = false;
            using (LoadSession session = LoadSession.Acquire(pid, () => cancel))
            {
                Expect<InvalidOperationException>(() => session.WaitForReady(IntPtr.Zero));
                session.Prepare(root, "payload.jar", "api.jar", root, "fixture", "test");
                cancel = true;
                Expect<OperationCanceledException>(() => session.WaitForReady(IntPtr.Zero));
            }
            Console.WriteLine("LOAD_SESSION_VERIFIED mutex=exclusive+released config=attempt-scoped log=stale+foreign-filtered cancellation=bounded");
        }
        finally { Directory.Delete(root, true); }
    }

    private static void Check(bool condition, string message)
    {
        if (!condition) throw new Exception(message);
    }

    private static void Expect<T>(Action action) where T : Exception
    {
        try { action(); }
        catch (T) { return; }
        throw new Exception("Expected " + typeof(T).Name);
    }
}
