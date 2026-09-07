using System;
using System.Diagnostics;
using System.IO;

namespace Moons.Shared
{
    /// <summary>Owns a process-local temporary file and its delete-then-move commit.</summary>
    internal static class StagedFile
    {
        // The caller owns directory creation, cache identity and content validation.
        // This preserves the launcher's existing replacement semantics; it is not atomic.
        internal static void Write(string target, Action<string> write)
        {
            string temporary = target + ".tmp-" + Process.GetCurrentProcess().Id;
            try
            {
                write(temporary);
                if (File.Exists(target)) File.Delete(target);
                File.Move(temporary, target);
            }
            finally
            {
                if (File.Exists(temporary)) File.Delete(temporary);
            }
        }
    }
}
