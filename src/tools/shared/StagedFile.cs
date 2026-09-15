using System;
using System.Diagnostics;
using System.IO;

namespace Moons.Shared
{
    /// <summary>Owns a process-local temporary file and publishes it atomically.</summary>
    internal static class StagedFile
    {
        // The caller owns directory creation, cache identity and content validation.
        internal static void Write(string target, Action<string> write)
        {
            string temporary = target + ".tmp-" + Process.GetCurrentProcess().Id;
            try
            {
                write(temporary);
                if (File.Exists(target)) File.Replace(temporary, target, null);
                else File.Move(temporary, target);
            }
            finally
            {
                if (File.Exists(temporary)) File.Delete(temporary);
            }
        }
    }
}
