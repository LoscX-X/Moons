package com.blanoir.moons.client.access;

import com.blanoir.moons.client.event.Event;
import com.blanoir.moons.client.event.EventThread;

import net.minecraft.resources.Identifier;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Resolves private game members before a live client starts using the feature host. */
public final class GameAccessVerification {
    private GameAccessVerification() {}

    public static void main(String[] arguments) throws Exception {
        Class.forName(GameAccess.class.getName(), true, GameAccess.class.getClassLoader());
        for (GameCapability capability : GameCapability.values()) {
            var status = GameAccess.capability(capability);
            if (!status.available()) {
                throw new AssertionError(
                        "Unavailable game capability " + capability, status.failure());
            }
        }
        verifyCapabilityFailureIsolation();
        // Construct actual descriptors, including lazy blit variants, without opening a GPU.
        int pipelines = 0;
        for (String name :
                new String[] {
                    "com.blanoir.moons.client.render.WorldLabelBackend",
                    "com.blanoir.moons.client.render.WorldOverlayRenderer",
                    "com.blanoir.moons.client.module.impl.network.backtrack.BacktrackRenderer"
                }) {
            Class<?> owner = Class.forName(name, true, GameAccess.class.getClassLoader());
            for (var field : owner.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())
                        && field.getType().getSimpleName().equals("RenderPipeline")) {
                    field.setAccessible(true);
                    verifyPipeline(field.get(null));
                    pipelines++;
                }
            }
        }
        Class<?> blit = Class.forName("com.blanoir.moons.client.render.VisualLayerBlit");
        Class<?> mode = Class.forName(blit.getName() + "$Mode");
        var create = blit.getDeclaredMethod("pipeline", mode);
        create.setAccessible(true);
        for (Object value : mode.getEnumConstants()) {
            verifyPipeline(create.invoke(null, value));
            pipelines++;
        }
        verifyRenderFailureIsolation();
        verifySubscriptionOwnership();
        System.out.println(
                "MOONS_RENDER_PIPELINES_VERIFIED count=" + pipelines + " shaders=resolved");
        System.out.println("MOONS_GAME_ACCESS_VERIFIED private-members=resolved");
    }

    private static void verifyPipeline(Object pipeline) throws Exception {
        Class<?> type = pipeline.getClass();
        Identifier location = (Identifier) type.getMethod("getLocation").invoke(pipeline);
        if (!location.getNamespace().equals("moons"))
            throw new AssertionError("Pipeline lost its namespace: " + location);
        try {
            verifyShader((Identifier) type.getMethod("getVertexShader").invoke(pipeline), ".vsh");
            verifyShader((Identifier) type.getMethod("getFragmentShader").invoke(pipeline), ".fsh");
        } catch (NoSuchMethodException renderPearl) {
            var shaders = (Map<?, ?>) type.getMethod("getShaders").invoke(pipeline);
            for (var entry : shaders.entrySet()) {
                String stage = ((Enum<?>) entry.getKey()).name();
                verifyShader(
                        (Identifier) entry.getValue(), stage.equals("VERTEX") ? ".vsh" : ".fsh");
            }
        }
    }

    private static void verifyCapabilityFailureIsolation() throws Exception {
        for (GameCapability capability : GameCapability.values()) {
            verifyCapabilityFailureIsolation(capability);
        }
        System.out.println(
                "MOONS_GAME_CAPABILITY_ISOLATION_VERIFIED capabilities=5 each=failed other=available");
    }

    private static void verifyCapabilityFailureIsolation(GameCapability failed) throws Exception {
        String holder =
                switch (failed) {
                    case INPUT -> "InputMembers";
                    case INTERACTION -> "InteractionMembers";
                    case PACKET -> "PacketMembers";
                    case RENDER -> "RenderMembers";
                    case HUD -> "HudMembers";
                };
        String member =
                switch (failed) {
                    case INPUT -> "key";
                    case INTERACTION -> "startAttack";
                    case PACKET -> "genericsFtw";
                    case RENDER -> "DEBUG_FILLED_SNIPPET";
                    case HUD -> "header";
                };
        ClassLoader parent = GameAccess.class.getClassLoader();
        ClassLoader faulted =
                new ClassLoader(parent) {
                    @Override
                    protected Class<?> loadClass(String name, boolean resolve)
                            throws ClassNotFoundException {
                        if (!name.equals(GameAccess.class.getName())
                                && !name.startsWith(GameAccess.class.getName() + "$")) {
                            return super.loadClass(name, resolve);
                        }
                        synchronized (getClassLoadingLock(name)) {
                            Class<?> loaded = findLoadedClass(name);
                            if (loaded == null) {
                                try (var input =
                                        parent.getResourceAsStream(
                                                name.replace('.', '/') + ".class")) {
                                    if (input == null) throw new ClassNotFoundException(name);
                                    byte[] bytes = input.readAllBytes();
                                    if (name.endsWith("$" + holder)) {
                                        var node = new org.objectweb.asm.tree.ClassNode();
                                        new org.objectweb.asm.ClassReader(bytes).accept(node, 0);
                                        boolean replaced = false;
                                        for (var method : node.methods) {
                                            for (var instruction : method.instructions) {
                                                if (instruction
                                                                instanceof
                                                                org.objectweb.asm.tree.LdcInsnNode
                                                                        constant
                                                        && member.equals(constant.cst)) {
                                                    constant.cst = "missingCapabilityMember";
                                                    replaced = true;
                                                }
                                            }
                                        }
                                        if (!replaced)
                                            throw new AssertionError(
                                                    "Missing fixture member "
                                                            + holder
                                                            + "."
                                                            + member);
                                        var writer = new org.objectweb.asm.ClassWriter(0);
                                        node.accept(writer);
                                        bytes = writer.toByteArray();
                                    }
                                    loaded = defineClass(name, bytes, 0, bytes.length);
                                } catch (java.io.IOException failure) {
                                    throw new ClassNotFoundException(name, failure);
                                }
                            }
                            if (resolve) resolveClass(loaded);
                            return loaded;
                        }
                    }
                };
        Class<?> access = Class.forName(GameAccess.class.getName(), true, faulted);
        var check = access.getMethod("capability", GameCapability.class);
        var broken = (GameCapability.Status) check.invoke(null, failed);
        if (broken.available() || broken.failure() == null)
            throw new AssertionError("Missing " + failed + " member must report failure");
        for (GameCapability capability : GameCapability.values()) {
            if (capability == failed) continue;
            var status = (GameCapability.Status) check.invoke(null, capability);
            if (!status.available())
                throw new AssertionError(
                        failed + " failure poisoned " + capability, status.failure());
        }
        if (((GameCapability.Status) check.invoke(null, failed)).available())
            throw new AssertionError("A failed resolver must not silently recover");
    }

    private static void verifyShader(Identifier shader, String extension) {
        String path =
                "assets/" + shader.getNamespace() + "/shaders/" + shader.getPath() + extension;
        if (GameAccess.class.getClassLoader().getResource(path) == null)
            throw new AssertionError("Missing pipeline shader: " + path);
    }

    private static void verifyRenderFailureIsolation() {
        Event<Object> event = new Event<>("render.fixture", EventThread.RENDER);
        int[] calls = new int[2];
        var failure = new IllegalStateException("render failure fixture");
        event.register(
                "broken",
                ignored -> {
                    calls[0]++;
                    throw failure;
                });
        event.register(
                "also-broken",
                ignored -> {
                    throw failure;
                });
        event.register("healthy", ignored -> calls[1]++);
        var output = new ByteArrayOutputStream();
        PrintStream previous = System.err;
        try (var capture = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            System.setErr(capture);
            for (int frame = 0; frame < 256; frame++) event.post(null);
        } finally {
            System.setErr(previous);
        }
        long reports =
                output.toString(StandardCharsets.UTF_8)
                        .lines()
                        .filter(line -> line.startsWith("[EventBus]"))
                        .count();
        if (reports != 2 || calls[0] != 256 || calls[1] != 256)
            throw new AssertionError("Render failures must remain isolated and rate-limited");
        System.out.println("MOONS_RENDER_FAILURE_VERIFIED frames=256 reports=2 healthy=256");
    }

    private static void verifySubscriptionOwnership() throws Exception {
        var event = new Event<Integer>();
        var calls = new java.util.ArrayList<String>();
        var explicit =
                event.subscribe(
                        "explicit",
                        com.blanoir.moons.client.event.EventPriority.HIGH,
                        value -> calls.add("explicit:" + value));
        var scope = new com.blanoir.moons.runtime.lifecycle.DefaultResourceScope();
        com.blanoir.moons.api.ScopedResources.run(
                scope, () -> event.register("scoped", value -> calls.add("scoped:" + value)));
        event.post(1);
        if (!calls.equals(java.util.List.of("explicit:1", "scoped:1")))
            throw new AssertionError("Subscription priority or order changed");
        scope.close();
        scope.close();
        event.post(2);
        explicit.close();
        explicit.close();
        event.post(3);
        if (!calls.equals(java.util.List.of("explicit:1", "scoped:1", "explicit:2"))
                || event.listenerCount() != 0)
            throw new AssertionError(
                    "Explicit and implicit ownership must close independently once");
        System.out.println(
                "MOONS_EVENT_OWNERSHIP_VERIFIED scoped explicit priority repeated-close");
    }
}
