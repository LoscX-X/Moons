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
}
