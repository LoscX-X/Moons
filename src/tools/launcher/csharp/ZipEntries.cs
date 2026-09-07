using System;
using System.IO;
using System.IO.Compression;

namespace Moons.WindowsLauncher
{
    /// <summary>ZIP entry I/O without payload selection, patch prefixes or deletion policy.</summary>
    internal static class ZipEntries
    {
        internal static bool IsDirectory(ZipArchiveEntry entry)
        {
            return entry.FullName.EndsWith("/", StringComparison.Ordinal);
        }

        internal static byte[] ReadBytes(ZipArchiveEntry entry)
        {
            using (Stream input = entry.Open())
            using (MemoryStream output = new MemoryStream())
            {
                input.CopyTo(output);
                return output.ToArray();
            }
        }

        internal static void Copy(ZipArchiveEntry source, ZipArchive target, string name)
        {
            ZipArchiveEntry destination = target.CreateEntry(name, CompressionLevel.Optimal);
            using (Stream input = source.Open())
            using (Stream output = destination.Open())
            {
                input.CopyTo(output);
            }
        }

        internal static void Write(ZipArchive target, string name, byte[] bytes)
        {
            ZipArchiveEntry destination = target.CreateEntry(name, CompressionLevel.Optimal);
            using (Stream output = destination.Open())
            {
                output.Write(bytes, 0, bytes.Length);
            }
        }
    }
}
