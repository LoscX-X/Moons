package com.blanoir.moons.runtime;

import com.blanoir.moons.api.Branding;
import com.blanoir.moons.api.AgentMode;
import com.blanoir.moons.api.bridge.RuntimeBridge;

import java.nio.file.Files;
import java.nio.file.Path;

/** Reflection entrypoint used by the agent-owned runtime class loader. */
public final class RuntimeEntrypoint {
    private RuntimeEntrypoint() { }

    /** Retained for older launchers that predate explicit version handoff. */
    public static RuntimeBridge start(Path home, Path outerJar, AgentMode mode) throws Exception {
        return start(home, outerJar, mode, "26.1.2");
    }

    public static RuntimeBridge start(
            Path home,
            Path outerJar,
            AgentMode mode,
            String minecraftVersion
    ) throws Exception {
        Files.createDirectories(home);
        DefaultRuntimeBridge runtime = new DefaultRuntimeBridge(
                mode, home, outerJar, minecraftVersion);
        System.out.println(Branding.prefix() + " Runtime started in " + mode
                + " mode for Minecraft " + minecraftVersion);
        return runtime;
    }
}
