package com.blanoir.moons.runtime;

import java.lang.reflect.Method;

/** Confirms the selected mapping version inside the actual game class loader. */
final class MinecraftVersionGuard {
    private MinecraftVersionGuard() {}

    static void verify(Object minecraft, String expectedVersion) {
        if (Boolean.getBoolean("moons.fixture")) return;
        if (minecraft == null) {
            throw new IllegalStateException(
                    "Minecraft instance is unavailable for version verification");
        }
        try {
            ClassLoader gameLoader = minecraft.getClass().getClassLoader();
            Class<?> sharedConstants =
                    Class.forName("net.minecraft.SharedConstants", true, gameLoader);
            Object currentVersion = sharedConstants.getMethod("getCurrentVersion").invoke(null);
            Method id = currentVersion.getClass().getMethod("id");
            String actualVersion = String.valueOf(id.invoke(currentVersion));
            if (!expectedVersion.equals(actualVersion)) {
                throw new IllegalStateException(
                        "Unsupported Minecraft version "
                                + actualVersion
                                + "; expected "
                                + expectedVersion);
            }
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(
                    "Unable to verify the active Minecraft version against " + expectedVersion,
                    failure);
        }
    }
}
