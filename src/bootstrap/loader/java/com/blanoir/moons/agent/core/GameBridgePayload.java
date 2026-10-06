package com.blanoir.moons.agent.core;

import com.blanoir.moons.api.Branding;
import com.blanoir.moons.api.bridge.AgentBridge;
import com.blanoir.moons.api.bridge.RuntimeBridge;

import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Owns loader-local bridge bytecode, type identity checks and delegate binding. */
final class GameBridgePayload {
    private GameBridgePayload() {}

    private static final String[] GAME_BRIDGE_CLASS_NAMES = {
        "com.blanoir.moons.api.bridge.RuntimeBridge",
        "com.blanoir.moons.api.bridge.NoopRuntimeBridge",
        "com.blanoir.moons.api.bridge.AgentBridge",
        RuntimeBridgeAdapter.NAME
    };

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
        ClassLoader payloadLoader = GameBridgePayload.class.getClassLoader();
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

    static void bind(
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

    static void verifyBootstrapApi() throws ClassNotFoundException {
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
}
