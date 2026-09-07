using System;
using System.IO;
using System.Security.Cryptography;

namespace Moons.Shared
{
    /// <summary>Digest primitives; callers own identifiers, truncation and verification policy.</summary>
    internal static class Hashing
    {
        internal static byte[] Sha256Bytes(byte[] bytes)
        {
            using (SHA256 sha = SHA256.Create())
            {
                return sha.ComputeHash(bytes);
            }
        }

        internal static string Sha256(byte[] bytes)
        {
            return Hex(Sha256Bytes(bytes));
        }

        internal static string Sha256(string path)
        {
            using (FileStream input = File.OpenRead(path))
            using (SHA256 sha = SHA256.Create())
            {
                return Hex(sha.ComputeHash(input));
            }
        }

        internal static string Hex(byte[] bytes)
        {
            return BitConverter.ToString(bytes).Replace("-", String.Empty).ToLowerInvariant();
        }
    }
}
