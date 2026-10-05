using System;
using System.ComponentModel;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;
using Microsoft.Win32.SafeHandles;

namespace Moons.Shared
{
    /// <summary>Shares the existing data directory with a legacy installation view.</summary>
    internal static class DirectoryJunction
    {
        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        private static extern bool CreateHardLink(string name, string existing, IntPtr security);
        [DllImport("kernel32.dll", SetLastError = true)]
        private static extern bool GetFileInformationByHandle(SafeFileHandle handle, [Out] uint[] information);

        internal static bool TryHardLink(string name, string existing)
        {
            return CreateHardLink(name, existing, IntPtr.Zero);
        }

        internal static bool SameFile(string first, string second)
        {
            using (var left = CreateFile(first, 0, 7, IntPtr.Zero, 3, 0, IntPtr.Zero))
            using (var right = CreateFile(second, 0, 7, IntPtr.Zero, 3, 0, IntPtr.Zero)) {
                var a = new uint[13]; var b = new uint[13];
                return !left.IsInvalid && !right.IsInvalid && GetFileInformationByHandle(left, a)
                    && GetFileInformationByHandle(right, b) && a[7] == b[7] && a[11] == b[11] && a[12] == b[12];
            }
        }
        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        private static extern SafeFileHandle CreateFile(string name, uint access, uint share,
            IntPtr security, uint creation, uint flags, IntPtr template);
        [DllImport("kernel32.dll", SetLastError = true)]
        private static extern bool DeviceIoControl(SafeFileHandle handle, uint control, byte[] input,
            int size, IntPtr output, int outputSize, out int returned, IntPtr overlapped);
        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        private static extern uint GetFinalPathNameByHandle(SafeFileHandle handle, StringBuilder path, uint size, uint flags);

        internal static void Ensure(string link, string target)
        {
            target = Path.GetFullPath(target).TrimEnd(Path.DirectorySeparatorChar);
            link = Path.GetFullPath(link);
            Directory.CreateDirectory(target);
            if (Directory.Exists(link)) {
                using (var handle = CreateFile(link, 0, 7, IntPtr.Zero, 3, 0x02000000, IntPtr.Zero)) {
                    if (handle.IsInvalid) throw new Win32Exception(Marshal.GetLastWin32Error());
                    var path = new StringBuilder(32768);
                    uint length = GetFinalPathNameByHandle(handle, path, (uint)path.Capacity, 0);
                    string actual = path.ToString();
                    if (actual.StartsWith("\\\\?\\UNC\\", StringComparison.Ordinal)) actual = "\\\\" + actual.Substring(8);
                    else if (actual.StartsWith("\\\\?\\", StringComparison.Ordinal)) actual = actual.Substring(4);
                    if (length == 0 || length >= path.Capacity || !String.Equals(actual.TrimEnd(Path.DirectorySeparatorChar), target, StringComparison.OrdinalIgnoreCase))
                        throw new IOException("Existing legacy data path does not refer to shared user data.");
                }
                return;
            }
            string substitute = target.StartsWith("\\\\", StringComparison.Ordinal)
                ? "\\??\\UNC\\" + target.Substring(2) : "\\??\\" + target;
            byte[] paths = Encoding.Unicode.GetBytes(substitute + "\0" + target + "\0");
            byte[] buffer = new byte[16 + paths.Length];
            Put(buffer, 0, BitConverter.GetBytes(0xA0000003u));
            Put(buffer, 4, BitConverter.GetBytes(checked((ushort)(8 + paths.Length))));
            Put(buffer, 10, BitConverter.GetBytes(checked((ushort)(substitute.Length * 2))));
            Put(buffer, 12, BitConverter.GetBytes(checked((ushort)((substitute.Length + 1) * 2))));
            Put(buffer, 14, BitConverter.GetBytes(checked((ushort)(target.Length * 2))));
            Put(buffer, 16, paths);
            Directory.CreateDirectory(link);
            using (var handle = CreateFile(link, 0x40000000, 7, IntPtr.Zero, 3, 0x02200000, IntPtr.Zero)) {
                int returned;
                if (handle.IsInvalid || !DeviceIoControl(handle, 0x000900A4, buffer, buffer.Length, IntPtr.Zero, 0, out returned, IntPtr.Zero)) {
                    var failure = new Win32Exception(Marshal.GetLastWin32Error(), "Cannot share legacy installation data");
                    handle.Dispose();
                    Directory.Delete(link, false);
                    throw failure;
                }
            }
        }

        private static void Put(byte[] buffer, int offset, byte[] value) { Buffer.BlockCopy(value, 0, buffer, offset, value.Length); }
    }
}
