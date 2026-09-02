package com.blanoir.moons.agent.core;

import com.blanoir.moons.agent.PayloadCache;
import com.blanoir.moons.api.AgentMode;
import com.blanoir.moons.api.bridge.RuntimeBridge;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.net.URLClassLoader;
import java.nio.file.Path;

final class RuntimeLoader implements AutoCloseable {
    private static final String API_PREFIX = "com.blanoir.moons.api.";

    private final URLClassLoader classLoader;
    private final RuntimeBridge bridge;

    private RuntimeLoader(URLClassLoader classLoader, RuntimeBridge bridge) {
        this.classLoader = classLoader;
        this.bridge = bridge;
    }

    static RuntimeLoader start(
            Instrumentation instrumentation,
            Path outerJar,
            Path home,
            AgentMode mode
    ) throws Exception {
        return start(instrumentation, outerJar, home, mode, VersionMappings.version());
    }

    static RuntimeLoader start(
            Instrumentation instrumentation,
            Path outerJar,
            Path home,
            AgentMode mode,
            String minecraftVersion
    ) throws Exception {
        return load(outerJar, home, mode, minecraftVersion, findGameClassLoader(instrumentation));
    }

    static RuntimeLoader startNative(
            Path outerJar,
            Path home,
            AgentMode mode,
            ClassLoader gameLoader
    ) throws Exception {
        return startNative(outerJar, home, mode, gameLoader, VersionMappings.version());
    }

    static RuntimeLoader startNative(
            Path outerJar,
            Path home,
            AgentMode mode,
            ClassLoader gameLoader,
            String minecraftVersion
    ) throws Exception {
        if (gameLoader == null) throw new IllegalArgumentException("gameLoader");
        return load(outerJar, home, mode, minecraftVersion, gameLoader);
    }

    private static RuntimeLoader load(
            Path outerJar,
            Path home,
            AgentMode mode,
            String minecraftVersion,
            ClassLoader gameLoader
    ) throws Exception {
        Path runtimeJar = PayloadCache.extract(
                outerJar,
                "META-INF/moons/runtime/moons-runtime.jar",
                "moons-runtime.jar"
        );
        ClassLoader bridgeParent = new BridgeParentClassLoader(
                gameLoader, RuntimeLoader.class.getClassLoader());
        URLClassLoader loader = new URLClassLoader(
                new java.net.URL[]{runtimeJar.toUri().toURL()}, bridgeParent);
        Thread thread = Thread.currentThread();
        ClassLoader previousContext = thread.getContextClassLoader();
        try {
            thread.setContextClassLoader(loader);
            Class<?> entrypoint = Class.forName(
                    "com.blanoir.moons.runtime.RuntimeEntrypoint",
                    true,
                    loader
            );
            Method start = entrypoint.getMethod(
                    "start", Path.class, Path.class, AgentMode.class, String.class);
            RuntimeBridge bridge = (RuntimeBridge) start.invoke(
                    null, home, outerJar, mode, minecraftVersion);
            return new RuntimeLoader(loader, bridge);
        } catch (Throwable failure) {
            loader.close();
            throw failure;
        } finally {
            thread.setContextClassLoader(previousContext);
        }
    }

    RuntimeBridge bridge() {
        return bridge;
    }

    @Override
    public void close() throws Exception {
        bridge.close();
        classLoader.close();
    }

    private static ClassLoader findGameClassLoader(Instrumentation instrumentation) {
        for (Class<?> loadedClass : instrumentation.getAllLoadedClasses()) {
            if (loadedClass.getName().equals("net.minecraft.client.Minecraft")) {
                ClassLoader loader = loadedClass.getClassLoader();
                if (loader != null) return loader;
            }
        }
        ClassLoader context = Thread.currentThread().getContextClassLoader();
        return context == null ? ClassLoader.getSystemClassLoader() : context;
    }

    /**
     * Lunar-style isolated game loaders do not always delegate arbitrary packages to
     * bootstrap. Keep one API identity while still exposing game classes to runtime.
     */
    private static final class BridgeParentClassLoader extends ClassLoader {
        private final ClassLoader gameLoader;
        private final ClassLoader payloadLoader;

        private BridgeParentClassLoader(
                ClassLoader gameLoader,
                ClassLoader payloadLoader
        ) {
            super(null);
            this.gameLoader = gameLoader;
            this.payloadLoader = payloadLoader;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve)
                throws ClassNotFoundException {
            if (name.startsWith(API_PREFIX)) {
                return Class.forName(name, false, null);
            }
            if (name.startsWith("java.") || name.startsWith("javax.")
                    || name.startsWith("jdk.") || name.startsWith("sun.")) {
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
