package com.blanoir.moons.agent.core;

import com.blanoir.moons.api.Branding;
import com.blanoir.moons.api.LoadMode;
import com.blanoir.moons.api.bridge.AgentBridge;
import com.blanoir.moons.api.bridge.RuntimeBridge;
import com.blanoir.moons.loader.VersionMappings;
import com.blanoir.moons.loader.common.asm.transform.TransformCoordinator;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Owns native runtime lifecycle, dependency configuration and startup status aggregation. */
final class NativeRuntimeSession {
    private final TransformCoordinator transforms;

    NativeRuntimeSession(TransformCoordinator transforms) {
        this.transforms = transforms;
    }

    private RuntimeLoader runtime;
    private ClassLoader runtimeGameLoader;
    private String dependencyManifest;
    private String dependencyHash;

    synchronized void configureDependencies(String manifest, String hash) {
        dependencyManifest = manifest;
        dependencyHash = hash;
    }

    synchronized String startupStatus() {
        if (runtime == null) return "WAITING:runtime-start";
        String status;
        try {
            status =
                    (String)
                            runtime.bridge()
                                    .getClass()
                                    .getMethod("startupStatus")
                                    .invoke(runtime.bridge());
        } catch (ReflectiveOperationException failure) {
            return "FAILED:startup-status:" + failure;
        }
        if (!status.startsWith("READY:")) return status;
        String hooks = transforms.readiness(runtimeGameLoader, true);
        if (!hooks.startsWith("READY:")) return hooks;
        int diagnostics = hooks.indexOf(';');
        return diagnostics < 0 ? status : status + hooks.substring(diagnostics);
    }

    /** Starts the normal runtime after the native worker has identified the game loader. */
    synchronized boolean startRuntime(
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
            GameBridgePayload.verifyBootstrapApi();
            System.setProperty("moons.home", home.toString());
            if (hardwareId != null && hardwareId.matches("MOONS(?:-[0-9A-F]{4}){6}")) {
                System.setProperty("moons.hwid", hardwareId);
            }
            RuntimeLoader next =
                    RuntimeLoader.startNative(
                            outerJar,
                            home,
                            LoadMode.JVMTI,
                            gameLoader,
                            VersionMappings.version(),
                            dependencyManifest,
                            dependencyHash);
            GameBridgePayload.bind(gameLoader, gameAgentBridge, gameRuntimeBridge, next.bridge());
            RuntimeBridge previous = AgentBridge.install(next.bridge());
            runtime = next;
            runtimeGameLoader = gameLoader;
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

    private static void log(Path home, String line) {
        try {
            Files.createDirectories(home.resolve("logs"));
            Files.writeString(
                    home.resolve("logs/bridge.log"),
                    line + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (Throwable ignored) {
        }
    }
}
