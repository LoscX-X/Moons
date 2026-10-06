package com.blanoir.moons.agent.core;

import com.blanoir.moons.api.Branding;
import com.blanoir.moons.api.LoadMode;
import com.blanoir.moons.api.bridge.AgentBridge;
import com.blanoir.moons.api.bridge.RuntimeBridge;
import com.blanoir.moons.loader.VersionMappings;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** Owns the 1.8.9 native runtime lifetime; preserves its existing dependency protocol. */
final class NativeRuntimeSession {
    private RuntimeLoader runtime;

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
                            outerJar, home, LoadMode.JVMTI, gameLoader, VersionMappings.version());
            GameBridgePayload.bind(gameLoader, gameAgentBridge, gameRuntimeBridge, next.bridge());
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
}
