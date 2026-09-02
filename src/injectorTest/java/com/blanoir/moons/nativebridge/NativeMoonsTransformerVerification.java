package com.blanoir.moons.nativebridge;

import com.blanoir.moons.api.bridge.AgentBridge;
import com.blanoir.moons.api.bridge.RuntimeBridge;
import net.minecraft.client.Minecraft;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/** Verifies the production tick hook and its fail-open game-loader bridge. */
public final class NativeMoonsTransformerVerification {
    private static final String BRIDGE_PACKAGE = "com.blanoir.moons.api.bridge.";

    private NativeMoonsTransformerVerification() { }

    public static void main(String[] arguments) throws Exception {
        verifyIsolatedGameBridge();

        AtomicInteger starts = new AtomicInteger();
        AtomicInteger ends = new AtomicInteger();
        RuntimeBridge runtime = new RuntimeBridge() {
            @Override
            public void onClientTickStart(Object minecraft) {
                starts.incrementAndGet();
            }

            @Override
            public void onClientTickEnd(Object minecraft) {
                ends.incrementAndGet();
            }
        };
        AgentBridge.install(runtime);

        Minecraft minecraft = new Minecraft();
        minecraft.tick();
        AgentBridge.uninstall(runtime);

        if (minecraft.ticks() != 1L || starts.get() != 1 || ends.get() != 1) {
            throw new AssertionError("Production tick hook failed: ticks=" + minecraft.ticks()
                    + ", starts=" + starts.get() + ", ends=" + ends.get());
        }
        System.out.println("MOONS_NATIVE_MOONS_VERIFIED ticks=" + minecraft.ticks()
                + " starts=" + starts.get() + " ends=" + ends.get());
    }

    private static void verifyIsolatedGameBridge() throws Exception {
        IsolatedClassLoader gameLoader = new IsolatedClassLoader();
        Class<?> runtimeBridge = gameLoader.define(
                BRIDGE_PACKAGE + "RuntimeBridge",
                readClassBytes(BRIDGE_PACKAGE + "RuntimeBridge"));
        Class<?> noopRuntimeBridge = gameLoader.define(
                BRIDGE_PACKAGE + "NoopRuntimeBridge",
                readClassBytes(BRIDGE_PACKAGE + "NoopRuntimeBridge"));
        Class<?> agentBridge = gameLoader.define(
                BRIDGE_PACKAGE + "AgentBridge",
                readClassBytes(BRIDGE_PACKAGE + "AgentBridge"));

        Object noop = runtimeBridge.getField("NOOP").get(null);
        if (noop.getClass() != noopRuntimeBridge) {
            throw new AssertionError("Isolated RuntimeBridge did not initialize its explicit NOOP");
        }
        agentBridge.getMethod("onClientTickStart", Object.class)
                .invoke(null, new Object[] { null });

        Object failingRuntime = Proxy.newProxyInstance(
                gameLoader,
                new Class<?>[] { runtimeBridge },
                (proxy, method, methodArguments) -> {
                    throw new LinkageError("isolated bridge failure");
                });
        agentBridge.getMethod("install", runtimeBridge).invoke(null, failingRuntime);

        ByteArrayOutputStream capturedError = new ByteArrayOutputStream();
        PrintStream previousError = System.err;
        try (PrintStream testError = new PrintStream(
                capturedError, true, StandardCharsets.UTF_8)) {
            try {
                System.setErr(testError);
                agentBridge.getMethod("onClientTickStart", Object.class)
                        .invoke(null, new Object[] { null });
            } finally {
                System.setErr(previousError);
            }
        }

        String report = capturedError.toString(StandardCharsets.UTF_8);
        if (!report.contains("Runtime failure in client tick start")
                || !report.contains("isolated bridge failure")) {
            throw new AssertionError("Isolated AgentBridge did not report the swallowed LinkageError");
        }
    }

    private static byte[] readClassBytes(String binaryName) throws IOException {
        String resourceName = binaryName.replace('.', '/') + ".class";
        ClassLoader testLoader = NativeMoonsTransformerVerification.class.getClassLoader();
        try (InputStream input = testLoader.getResourceAsStream(resourceName)) {
            if (input == null) {
                throw new IOException("Missing test classpath resource " + resourceName);
            }
            return input.readAllBytes();
        }
    }

    private static final class IsolatedClassLoader extends ClassLoader {
        private IsolatedClassLoader() {
            super(null);
        }

        private Class<?> define(String binaryName, byte[] bytes) {
            return defineClass(binaryName, bytes, 0, bytes.length);
        }
    }
}
