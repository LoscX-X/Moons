package com.blanoir.tempos.runtime;

import com.blanoir.tempos.api.Branding;
import com.blanoir.tempos.api.LoadMode;
import com.blanoir.tempos.api.bridge.RuntimeBridge;

import java.nio.file.Files;
import java.nio.file.Path;

/** Reflection entrypoint used by the agent-owned runtime class loader. */
public final class RuntimeEntrypoint {
    private RuntimeEntrypoint() {}

    public static RuntimeBridge start(
            Path home,
            Path outerJar,
            LoadMode mode,
            String minecraftVersion,
            Path dependencyRoot,
            Path dependencyModule)
            throws Exception {
        Files.createDirectories(home);
        return new DefaultRuntimeBridge(
                mode, home, outerJar, minecraftVersion, dependencyRoot, dependencyModule);
    }

    public static RuntimeBridge start(
            Path home, Path outerJar, LoadMode mode, String minecraftVersion) throws Exception {
        Files.createDirectories(home);
        DefaultRuntimeBridge runtime =
                new DefaultRuntimeBridge(mode, home, outerJar, minecraftVersion);
        System.out.println(
                Branding.prefix()
                        + " Runtime started in "
                        + mode
                        + " mode for Minecraft "
                        + minecraftVersion);
        return runtime;
    }
}
