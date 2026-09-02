package com.blanoir.moons.agent.core;

import java.lang.instrument.Instrumentation;

/** Read-only runtime detection without linking the agent to Minecraft classes. */
final class MinecraftEnvironment {
    private MinecraftEnvironment() { }

    static String detectVersion(Instrumentation instrumentation) {
        for (Class<?> type : instrumentation.getAllLoadedClasses()) {
            if (!type.getName().equals("net.minecraft.SharedConstants")) continue;
            try {
                Object version = type.getMethod("getCurrentVersion").invoke(null);
                Object id = version.getClass().getMethod("id").invoke(version);
                return String.valueOf(id);
            } catch (ReflectiveOperationException failure) {
                return "unknown";
            }
        }
        return "pending";
    }
}
