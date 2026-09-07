package com.blanoir.moons.agent.core;

import com.blanoir.moons.agent.PayloadCache;
import com.blanoir.moons.api.AgentMode;
import com.blanoir.moons.api.bridge.RuntimeBridge;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

final class RuntimeLoader implements AutoCloseable {
    private static final String API_PREFIX = "com.blanoir.moons.api.";

    private final URLClassLoader classLoader;
    private final RuntimeBridge bridge;

    private RuntimeLoader(URLClassLoader classLoader, RuntimeBridge bridge) {
        this.classLoader = classLoader;
        this.bridge = bridge;
    }

    static RuntimeLoader startNative(
            Path outerJar,
            Path home,
            AgentMode mode,
            ClassLoader gameLoader,
            String minecraftVersion)
            throws Exception {
        if (gameLoader == null) throw new IllegalArgumentException("gameLoader");
        return load(outerJar, home, mode, minecraftVersion, gameLoader);
    }

    private static RuntimeLoader load(
            Path outerJar,
            Path home,
            AgentMode mode,
            String minecraftVersion,
            ClassLoader gameLoader)
            throws Exception {
        Path runtimeJar =
                PayloadCache.extract(
                        outerJar, "META-INF/moons/runtime/moons-runtime.jar", "moons-runtime.jar");
        ClassLoader bridgeParent =
                new BridgeParentClassLoader(gameLoader, RuntimeLoader.class.getClassLoader());
        Path uiRuntime = resolveUiRuntime(home);
        java.net.URL[] runtimeUrls =
                uiRuntime == null
                        ? new java.net.URL[] {runtimeJar.toUri().toURL()}
                        : new java.net.URL[] {
                            runtimeJar.toUri().toURL(), uiRuntime.toUri().toURL()
                        };
        URLClassLoader loader = new URLClassLoader(runtimeUrls, bridgeParent);
        Thread thread = Thread.currentThread();
        ClassLoader previousContext = thread.getContextClassLoader();
        try {
            thread.setContextClassLoader(loader);
            Class<?> entrypoint =
                    Class.forName("com.blanoir.moons.runtime.RuntimeEntrypoint", true, loader);
            Method start =
                    entrypoint.getMethod(
                            "start", Path.class, Path.class, AgentMode.class, String.class);
            RuntimeBridge bridge =
                    (RuntimeBridge) start.invoke(null, home, outerJar, mode, minecraftVersion);
            return new RuntimeLoader(loader, bridge);
        } catch (Throwable failure) {
            loader.close();
            throw failure;
        } finally {
            thread.setContextClassLoader(previousContext);
        }
    }

    private static Path resolveUiRuntime(Path home) throws Exception {
        Path libraryRoot = home.resolve("libraries").toAbsolutePath().normalize();
        Path pointer = libraryRoot.resolve("moons-ui-runtime.current");
        if (!Files.isRegularFile(pointer)) return null;

        String relative = Files.readString(pointer, StandardCharsets.UTF_8).trim();
        if (relative.isEmpty()) return null;
        Path library = libraryRoot.resolve(relative).normalize();
        if (!library.startsWith(libraryRoot)) {
            throw new IllegalStateException("Invalid Moons UI runtime pointer: " + pointer);
        }
        if (!Files.isRegularFile(library)) {
            throw new IllegalStateException("Moons UI runtime is missing: " + library);
        }
        return library;
    }

    RuntimeBridge bridge() {
        return bridge;
    }

    @Override
    public void close() throws IOException {
        try (classLoader) {
            bridge.close();
        }
    }

    /**
     * Isolated game loaders do not always delegate arbitrary packages to
     * bootstrap. Keep one API identity while still exposing game classes to runtime.
     */
    private static final class BridgeParentClassLoader extends ClassLoader {
        private final ClassLoader gameLoader;
        private final ClassLoader payloadLoader;

        private BridgeParentClassLoader(ClassLoader gameLoader, ClassLoader payloadLoader) {
            super(null);
            this.gameLoader = gameLoader;
            this.payloadLoader = payloadLoader;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.startsWith(API_PREFIX)) {
                return Class.forName(name, false, null);
            }
            if (name.startsWith("java.")
                    || name.startsWith("javax.")
                    || name.startsWith("jdk.")
                    || name.startsWith("sun.")) {
                try {
                    return Class.forName(name, false, null);
                } catch (ClassNotFoundException ignored) {
                    // Some platform modules are visible through the payload loader only.
                }
            }
            try {
                return Class.forName(name, false, gameLoader);
            } catch (ClassNotFoundException gameMiss) {
                try {
                    return Class.forName(name, false, payloadLoader);
                } catch (ClassNotFoundException payloadMiss) {
                    payloadMiss.addSuppressed(gameMiss);
                    throw payloadMiss;
                }
            }
        }
    }
}
