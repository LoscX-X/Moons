package com.blanoir.moons.runtime;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

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
            String actualVersion;
            if ("1.8.9".equals(expectedVersion)) {
                actualVersion = legacyVersion(minecraft);
            } else {
                ClassLoader gameLoader = minecraft.getClass().getClassLoader();
                Class<?> sharedConstants =
                        Class.forName("net.minecraft.SharedConstants", true, gameLoader);
                Object currentVersion = sharedConstants.getMethod("getCurrentVersion").invoke(null);
                Method id = currentVersion.getClass().getMethod("id");
                actualVersion = String.valueOf(id.invoke(currentVersion));
            }
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

    private static String legacyVersion(Object minecraft) throws ReflectiveOperationException {
        Class<?> type = minecraft.getClass();
        String getter;
        String expectedDigest;
        switch (type.getName()) {
            case "ave" -> {
                getter = "c"; // MCP stable 22: Minecraft.func_175600_c/getVersion.
                expectedDigest = "430a17aa1911bcd0251aa4dcecb013b6c5b251bb5d0122c7305de0e36dc33e82";
            }
            case "net.minecraft.client.Minecraft" -> {
                getter = "getVersion";
                expectedDigest = "3e524fe649fd4c7f74f41dd6cb78a21888337c76418b13e860f7e8df78dd441b";
            }
            default ->
                    throw new IllegalStateException(
                            "Unsupported Minecraft 1.8.9 runtime class " + type.getName());
        }
        Method method = type.getMethod(getter);
        if (method.getReturnType() != String.class)
            throw new IllegalStateException("Unexpected Minecraft version getter signature");
        String reported = (String) method.invoke(minecraft);
        // getVersion is only the launcher-supplied --version label. Verify the original
        // class resource too, so a different 1.8 client with that label is never accepted.
        // Retransformation changes VM bytecode, not the underlying jar resource.
        String resource = "/" + type.getName().replace('.', '/') + ".class";
        try (var input = type.getResourceAsStream(resource)) {
            if (input == null)
                throw new IllegalStateException(
                        "Missing Minecraft version fingerprint resource " + resource);
            String actualDigest =
                    HexFormat.of()
                            .formatHex(
                                    MessageDigest.getInstance("SHA-256")
                                            .digest(input.readAllBytes()));
            if (!expectedDigest.equals(actualDigest))
                throw new IllegalStateException(
                        "Unsupported Minecraft core bytecode; expected verified vanilla 1.8.9 or its MCP stable 22 mapping");
        } catch (IOException error) {
            throw new UncheckedIOException("Unable to read Minecraft version fingerprint", error);
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
        return reported;
    }
}
