package com.blanoir.moons.agent.core;

import com.blanoir.moons.loader.VersionMappings;
import com.blanoir.moons.loader.common.asm.transform.TransformCoordinator;

import java.io.IOException;

/** Stable JNI boundary; delegates transformation, bridge payload and runtime lifecycle. */
public final class NativeTransformerBridge {
    public static final int FLAG_RETRANSFORM = 1;
    private static final TransformCoordinator TRANSFORMS =
            new TransformCoordinator(VersionMappings.create());
    private static final NativeRuntimeSession RUNTIME = new NativeRuntimeSession(TRANSFORMS);

    private NativeTransformerBridge() {}

    public static synchronized void configureDependencies(String manifest, String hash) {
        RUNTIME.configureDependencies(manifest, hash);
    }

    public static synchronized void beginAttempt(String attempt) {
        TRANSFORMS.beginAttempt(attempt);
    }

    public static void retransformAccepted(String className, ClassLoader loader, boolean success) {
        TRANSFORMS.accepted(loader, className, success);
    }

    public static void transformDeliveryFailed(String className, ClassLoader loader) {
        TRANSFORMS.deliveryFailed(loader, className);
    }

    public static boolean requiredClass(String className) {
        return TRANSFORMS.requiredClass(className);
    }

    public static synchronized String startupStatus() {
        return RUNTIME.startupStatus();
    }

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
            String homeText,
            String outerJarText,
            ClassLoader gameLoader,
            String hardwareId,
            Class<?> gameAgentBridge,
            Class<?> gameRuntimeBridge) {
        return RUNTIME.startRuntime(
                homeText, outerJarText, gameLoader, hardwareId, gameAgentBridge, gameRuntimeBridge);
    }

    /** Returns null when the class is not targeted or should remain unchanged. */
    public static byte[] transform(
            String className, ClassLoader loader, byte[] classBytes, int flags) {
        if (className == null || classBytes == null || !TRANSFORMS.targets(className)) {
            return null;
        }
        return TRANSFORMS.transform(loader, className, classBytes);
    }
}
