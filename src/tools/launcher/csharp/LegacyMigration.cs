using System;

namespace Tempos.WindowsLauncher
{
    // Old identifiers are confined here so a renamed loader cannot double-load an older bridge.
    internal static class LegacyMigration
    {
        private const string BridgeModuleName = "moons-bridge.dll";

        internal static void RequireFreshBridge(string moduleName)
        {
            if (String.Equals(moduleName, BridgeModuleName, StringComparison.OrdinalIgnoreCase))
                throw new InvalidOperationException(
                    "An earlier client bridge is active. Restart Minecraft before loading Tempos.");
        }
    }
}
