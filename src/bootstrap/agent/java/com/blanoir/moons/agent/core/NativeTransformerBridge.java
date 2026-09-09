package com.blanoir.moons.agent.core;

import com.blanoir.moons.api.AgentMode;
import com.blanoir.moons.api.Branding;
import com.blanoir.moons.api.bridge.AgentBridge;
import com.blanoir.moons.api.bridge.RuntimeBridge;

import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * JNI boundary used by the production bridge and the JVMTI verification transport.
 * Minecraft-specific transformation remains entirely in Java/ASM.
 */
public final class NativeTransformerBridge {
    public static final int FLAG_RETRANSFORM = 1;

    private static final String[] GAME_BRIDGE_CLASS_NAMES = {
        "com.blanoir.moons.api.bridge.RuntimeBridge",
        "com.blanoir.moons.api.bridge.NoopRuntimeBridge",
        "com.blanoir.moons.api.bridge.AgentBridge",
        RuntimeBridgeAdapter.NAME
    };

    private static final MoonsTransformer TRANSFORMER =
            new MoonsTransformer(VersionMappings.create());
    private static RuntimeLoader runtime;

    private NativeTransformerBridge() {}

    /** Exact internal names used by native code to avoid retransformation of unrelated classes. */
    public static String[] targetClassNames() {
        return TRANSFORMER.targetClassNames();
    }

    /**
     * Classes which native code defines directly in the hostile game loader.
     *
     * <p>A non-delegating game loader may not expose arbitrary application packages
     * to bootstrap, so transformed game classes cannot link the bootstrap copy of
     * AgentBridge. A loader-local facade keeps the bytecode linkage local while a
     * typed MethodHandles forward calls to the real bootstrap/runtime bridge.</p>
     */
    public static String[] gameBridgeClassNames() {
        return GAME_BRIDGE_CLASS_NAMES.clone();
    }

    public static byte[][] gameBridgeClassBytes() throws IOException {
        byte[][] result = new byte[GAME_BRIDGE_CLASS_NAMES.length][];
        Map<String, Integer> wanted = new HashMap<>();
        for (int index = 0; index < GAME_BRIDGE_CLASS_NAMES.length; index++) {
            if (GAME_BRIDGE_CLASS_NAMES[index].equals(RuntimeBridgeAdapter.NAME)) {
                result[index] = RuntimeBridgeAdapter.classBytes();
                continue;
            }
            wanted.put(GAME_BRIDGE_CLASS_NAMES[index].replace('.', '/') + ".class", index);
        }
        ClassLoader payloadLoader = NativeTransformerBridge.class.getClassLoader();
        try (InputStream nested =
                payloadLoader.getResourceAsStream("META-INF/moons/bootstrap/moons-api.jar")) {
            if (nested == null) {
                throw new IOException("Missing embedded moons-api.jar");
            }
            try (ZipInputStream zip = new ZipInputStream(nested)) {
                for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                    Integer index = wanted.get(entry.getName());
                    if (index != null) result[index] = zip.readAllBytes();
                }
            }
        }
        for (int index = 0; index < result.length; index++) {
            if (result[index] == null) {
                throw new IOException(
                        "Missing embedded game bridge class " + GAME_BRIDGE_CLASS_NAMES[index]);
            }
        }
        return result;
    }

    /** Starts the normal runtime after the native worker has identified the game loader. */
    public static synchronized boolean startRuntime(
            String homeText,
            String outerJarText,
            ClassLoader gameLoader,
            String hardwareId,
            Class<?> gameAgentBridge,
            Class<?> gameRuntimeBridge) {
        if (runtime != null) {
            if (AgentBridge.isInstalled(runtime.bridge())) return true;
            RuntimeLoader stale = runtime;
            runtime = null;
            try {
                stale.close();
            } catch (Exception failure) {
                System.err.println(
                        Branding.prefix()
                                + " Stale native runtime did not close cleanly: "
                                + failure);
            }
        }
        try {
            Path home = Path.of(homeText).toAbsolutePath().normalize();
            Path outerJar = Path.of(outerJarText).toAbsolutePath().normalize();
            verifyBootstrapApi();
            System.setProperty("moons.home", home.toString());
            if (hardwareId != null && hardwareId.matches("MOONS(?:-[0-9A-F]{4}){6}")) {
                System.setProperty("moons.hwid", hardwareId);
            }
            RuntimeLoader next =
                    RuntimeLoader.startNative(
                            outerJar, home, AgentMode.JVMTI, gameLoader, VersionMappings.version());
            bindGameBridge(gameLoader, gameAgentBridge, gameRuntimeBridge, next.bridge());
            RuntimeBridge previous = AgentBridge.install(next.bridge());
            runtime = next;
            if (previous != RuntimeBridge.NOOP) previous.close();
            log(
                    home,
                    "active: mode=JVMTI; apiLoader=bootstrap; gameLoader="
                            + gameLoader
                            + "; payload="
                            + outerJar);
            System.out.println(
                    Branding.prefix() + " Native JVMTI runtime active; gameLoader=" + gameLoader);
            return true;
        } catch (Throwable failure) {
            try {
                log(Path.of(homeText).toAbsolutePath().normalize(), "start failed: " + failure);
            } catch (Throwable ignored) {
            }
            System.err.println(
                    Branding.prefix() + " Native JVMTI runtime startup failed: " + failure);
            failure.printStackTrace(System.err);
            return false;
        }
    }

    private static void bindGameBridge(
            ClassLoader gameLoader,
            Class<?> gameAgentBridge,
            Class<?> gameRuntimeBridge,
            RuntimeBridge delegate)
            throws ReflectiveOperationException {
        if (gameAgentBridge.getClassLoader() != gameLoader
                || gameRuntimeBridge.getClassLoader() != gameLoader) {
            throw new IllegalStateException("Game bridge was not defined by the game loader");
        }
        Class<?> adapter = Class.forName(RuntimeBridgeAdapter.NAME, true, gameLoader);
        Object proxy =
                adapter.getConstructor(MethodHandle[].class)
                        .newInstance((Object) RuntimeBridgeAdapter.handles(delegate));
        Method install = gameAgentBridge.getMethod("install", gameRuntimeBridge);
        install.invoke(null, proxy);
    }

    private static void verifyBootstrapApi() throws ClassNotFoundException {
        Class<?> bootstrapRuntimeBridge =
                Class.forName("com.blanoir.moons.api.bridge.RuntimeBridge", false, null);
        Class<?> bootstrapAgentBridge =
                Class.forName("com.blanoir.moons.api.bridge.AgentBridge", false, null);
        if (bootstrapRuntimeBridge != RuntimeBridge.class
                || bootstrapAgentBridge != AgentBridge.class
                || RuntimeBridge.class.getClassLoader() != null
                || AgentBridge.class.getClassLoader() != null) {
            throw new IllegalStateException(
                    Branding.name() + " API is not uniquely defined by the bootstrap loader");
        }
    }

    private static void log(Path home, String line) {
        try {
            Files.createDirectories(home);
            Files.writeString(
                    home.resolve("bridge.log"),
                    line + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (Throwable ignored) {
        }
    }

    /** Returns null when the class is not targeted or should remain unchanged. */
    public static byte[] transform(
            String className, ClassLoader loader, byte[] classBytes, int flags) {
        if (className == null || classBytes == null || !TRANSFORMER.targets(className)) {
            return null;
        }
        return TRANSFORMER.transform(loader, className, classBytes);
    }
}
