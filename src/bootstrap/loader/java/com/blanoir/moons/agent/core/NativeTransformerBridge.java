package com.blanoir.moons.agent.core;

import com.blanoir.moons.loader.VersionMappings;
import com.blanoir.moons.loader.common.asm.transform.TransformCoordinator;

import java.io.IOException;

/** Stable JNI boundary delegating bytecode, bridge payload and runtime lifetime. */
public final class NativeTransformerBridge {
    public static final int FLAG_RETRANSFORM = 1;
    private static final TransformCoordinator TRANSFORMS =
            new TransformCoordinator(VersionMappings.create());
    private static final NativeRuntimeSession RUNTIME = new NativeRuntimeSession();

    private NativeTransformerBridge() {}

    public static String[] targetClassNames() {
        return TRANSFORMS.targetClassNames();
    }

    public static String[] gameBridgeClassNames() {
        return GameBridgePayload.gameBridgeClassNames();
    }

    public static byte[][] gameBridgeClassBytes() throws IOException {
        return GameBridgePayload.gameBridgeClassBytes();
    }

    public static synchronized boolean startRuntime(
            String home,
            String outerJar,
            ClassLoader gameLoader,
            String hardwareId,
            Class<?> gameAgentBridge,
            Class<?> gameRuntimeBridge) {
        return RUNTIME.startRuntime(
                home, outerJar, gameLoader, hardwareId, gameAgentBridge, gameRuntimeBridge);
    }

    public static byte[] transform(String className, ClassLoader loader, byte[] bytes, int flags) {
        return className == null || bytes == null || !TRANSFORMS.targets(className)
                ? null
                : TRANSFORMS.transform(loader, className, bytes);
    }
}
