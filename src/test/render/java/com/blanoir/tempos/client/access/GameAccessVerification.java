package com.blanoir.tempos.client.access;

import com.blanoir.tempos.client.event.Event;
import com.blanoir.tempos.client.event.EventThread;

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
        if (Boolean.getBoolean("tempos.verification.neoforge")) {
            Class<?> keys = net.minecraft.client.KeyMapping.class;
            Class<?> extension = Class.forName(
                    "net.neoforged.neoforge.client.extensions.IKeyMappingExtension", false, keys.getClassLoader());
            if (!extension.isAssignableFrom(keys))
                throw new AssertionError("The loaded KeyMapping is not NeoForge-patched: " + keys.getProtectionDomain().getCodeSource());
            System.out.println("TEMPOS_NEOFORGE_CLASS_ORIGIN_VERIFIED " + keys.getProtectionDomain().getCodeSource());
        }
        Class.forName(GameAccess.class.getName(), true, GameAccess.class.getClassLoader());
        for (GameCapability capability : GameCapability.values()) {
            var status = GameAccess.capability(capability);
            if (!status.available()) {
                throw new AssertionError(
                        "Unavailable game capability " + capability, status.failure());
            }
        }
        verifyCapabilityFailureIsolation();
        verifyVersionAdapters();
        // Construct actual descriptors, including lazy blit variants, without opening a GPU.
        int pipelines = 0;
        for (String name :
                new String[] {
                    "com.blanoir.tempos.client.render.WorldLabelPipeline",
                    "com.blanoir.tempos.client.render.WorldOverlayRenderer",
                    "com.blanoir.tempos.client.module.impl.network.backtrack.BacktrackRenderer"
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
        Class<?> blit = Class.forName("com.blanoir.tempos.client.render.VisualLayerBlit");
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
                "TEMPOS_RENDER_PIPELINES_VERIFIED count=" + pipelines + " shaders=resolved");
        System.out.println("TEMPOS_GAME_ACCESS_VERIFIED private-members=resolved");
    }

    private static void verifyPipeline(Object pipeline) throws Exception {
        Class<?> type = pipeline.getClass();
        Identifier location = (Identifier) type.getMethod("getLocation").invoke(pipeline);
        if (!location.getNamespace().equals(com.blanoir.tempos.client.config.TemposConfig.MOD_ID))
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

    private static void verifyVersionAdapters() throws Exception {
        var color = net.minecraft.network.chat.Style.EMPTY.withColor(0x123456);
        var text = net.minecraft.network.chat.Component.literal("参数").withStyle(color)
                .append(net.minecraft.network.chat.Component.literal(" cache"));
        var flat = MinecraftClientAccess.flatComponents(text);
        if (!flat.stream().map(net.minecraft.network.chat.Component::getString)
                .collect(java.util.stream.Collectors.joining()).equals("参数 cache")
                || flat.getFirst().getStyle().getColor().getValue() != 0x123456)
            throw new AssertionError("Styled component flattening changed text or color");
        var visited = new StringBuilder();
        MinecraftClientAccess.visitStyled(text, (style, part) -> {
            visited.append(part);
            return java.util.Optional.empty();
        }, net.minecraft.network.chat.Style.EMPTY);
        if (!visited.toString().equals("参数 cache")
                || MinecraftClientAccess.guiTexturedPipeline() == null
                || MinecraftClientAccess.guiPremultipliedPipeline() == null)
            throw new AssertionError("Text or GUI pipeline adapter is unavailable");
        Class<?> impulseType;
        try { impulseType = Class.forName("net.minecraft.network.protocol.game.ClientboundAddEntityMotionPacket"); }
        catch (ClassNotFoundException olderProtocol) { return; }
        var delta = new net.minecraft.world.phys.Vec3(1.25, 0.5, -0.25);
        var packet = (net.minecraft.network.protocol.Packet<?>) impulseType
                .getConstructor(int.class, net.minecraft.world.phys.Vec3.class).newInstance(42, delta);
        if (!MinecraftClientAccess.playerImpulse(packet, 42).equals(java.util.Optional.of(delta))
                || MinecraftClientAccess.playerImpulse(packet, 43).isPresent())
            throw new AssertionError("Additive motion lost its impulse or targeted another entity");
        System.out.println("TEMPOS_PRE2_ADAPTERS_VERIFIED styled-text gui-pipelines entity-scoped-impulse");
    }

    private static void verifyCapabilityFailureIsolation() throws Exception {
        for (GameCapability capability : GameCapability.values()) {
            verifyCapabilityFailureIsolation(capability);
        }
        System.out.println(
                "TEMPOS_GAME_CAPABILITY_ISOLATION_VERIFIED capabilities=5 each=failed other=available");
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
                    case RENDER -> "name";
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
        System.out.println("TEMPOS_RENDER_FAILURE_VERIFIED frames=256 reports=2 healthy=256");
    }

    private static void verifySubscriptionOwnership() throws Exception {
        var event = new Event<Integer>();
        var calls = new java.util.ArrayList<String>();
        var explicit =
                event.subscribe(
                        "explicit",
                        com.blanoir.tempos.client.event.EventPriority.HIGH,
                        value -> calls.add("explicit:" + value));
        var scope = new com.blanoir.tempos.runtime.lifecycle.DefaultResourceScope();
        com.blanoir.tempos.api.ScopedResources.run(
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
                "TEMPOS_EVENT_OWNERSHIP_VERIFIED scoped explicit priority repeated-close");
    }
}
