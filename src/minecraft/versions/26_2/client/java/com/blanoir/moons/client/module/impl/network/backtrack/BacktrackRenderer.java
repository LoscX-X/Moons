package com.blanoir.moons.client.module.impl.network.backtrack;

import com.blanoir.moons.client.access.GameAccess;
import com.blanoir.moons.client.config.MoonsConfig;
import com.blanoir.moons.client.render.world.WorldOverlayBuffer;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.AABB;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Through-walls Backtrack box renderer for the 26.2 staged-buffer API. */
public final class BacktrackRenderer {
    private static final RenderPipeline FILLED_PIPELINE =
            registerPipeline("pipeline/moons_backtrack_filled", PrimitiveTopology.QUADS);
    private static final RenderPipeline LINE_PIPELINE =
            registerPipeline("pipeline/moons_backtrack_lines", PrimitiveTopology.LINES);
    private static final Matrix4f IDENTITY = new Matrix4f();

    private static final int[][] FACES = {
        {0, 1, 3, 2}, {4, 5, 7, 6}, {0, 1, 5, 4},
        {2, 3, 7, 6}, {0, 2, 6, 4}, {1, 3, 7, 5}
    };
    private static final int[][] EDGES = {
        {0, 1}, {1, 3}, {3, 2}, {2, 0},
        {4, 5}, {5, 7}, {7, 6}, {6, 4},
        {0, 4}, {1, 5}, {3, 7}, {2, 6}
    };

    private BacktrackRenderer() {}

    public static void renderBox(
            PoseStack matrices, AABB box, int fillArgb, int outlineArgb, String label) {
        Minecraft client = Minecraft.getInstance();
        if (client == null || (alpha(fillArgb) == 0 && alpha(outlineArgb) == 0)) {
            return;
        }
        Vector4f[] corners = transformedCorners(matrices.last().pose(), box);
        if (alpha(fillArgb) > 0) {
            drawBoxMesh(
                    client,
                    FILLED_PIPELINE,
                    List.<Vector4f[]>of(corners),
                    FACES,
                    fillArgb,
                    label + " fill");
        }
        if (alpha(outlineArgb) > 0) {
            drawBoxMesh(
                    client,
                    LINE_PIPELINE,
                    List.<Vector4f[]>of(corners),
                    EDGES,
                    outlineArgb,
                    label + " outline");
        }
    }

    /** Collects several transformed boxes into one fill and one outline GPU submission. */
    public static final class BoxBatch {
        private final List<Vector4f[]> boxes = new ArrayList<>();

        public void add(PoseStack matrices, AABB box) {
            boxes.add(transformedCorners(matrices.last().pose(), box));
        }

        public void render(int fillArgb, int outlineArgb, String label) {
            Minecraft client = Minecraft.getInstance();
            if (client == null || boxes.isEmpty()) return;
            if (alpha(fillArgb) > 0) {
                drawBoxMesh(client, FILLED_PIPELINE, boxes, FACES, fillArgb, label + " fill");
            }
            if (alpha(outlineArgb) > 0) {
                drawBoxMesh(client, LINE_PIPELINE, boxes, EDGES, outlineArgb, label + " outline");
            }
        }
    }

    private static Vector4f[] transformedCorners(Matrix4fc transform, AABB box) {
        float[][] values = {
            {(float) box.minX, (float) box.minY, (float) box.minZ},
            {(float) box.maxX, (float) box.minY, (float) box.minZ},
            {(float) box.minX, (float) box.maxY, (float) box.minZ},
            {(float) box.maxX, (float) box.maxY, (float) box.minZ},
            {(float) box.minX, (float) box.minY, (float) box.maxZ},
            {(float) box.maxX, (float) box.minY, (float) box.maxZ},
            {(float) box.minX, (float) box.maxY, (float) box.maxZ},
            {(float) box.maxX, (float) box.maxY, (float) box.maxZ}
        };
        Vector4f[] result = new Vector4f[values.length];
        for (int index = 0; index < values.length; index++) {
            result[index] =
                    transform.transform(
                            new Vector4f(
                                    values[index][0], values[index][1], values[index][2], 1.0f));
        }
        return result;
    }

    private static void drawBoxMesh(
            Minecraft client,
            RenderPipeline pipeline,
            List<Vector4f[]> boxes,
            int[][] groups,
            int color,
            String label) {
        float red = ((color >>> 16) & 0xFF) / 255.0f;
        float green = ((color >>> 8) & 0xFF) / 255.0f;
        float blue = (color & 0xFF) / 255.0f;
        float opacity = alpha(color) / 255.0f;
        WorldOverlayBuffer.draw(
                client,
                pipeline,
                label,
                builder -> {
                    for (Vector4f[] corners : boxes) {
                        for (int[] group : groups) {
                            for (int cornerIndex : group) {
                                Vector4f corner = corners[cornerIndex];
                                vertex(builder, corner, red, green, blue, opacity);
                            }
                        }
                    }
                });
    }

    private static void vertex(
            VertexConsumer builder,
            Vector4f point,
            float red,
            float green,
            float blue,
            float alpha) {
        builder.addVertex(IDENTITY, point.x, point.y, point.z).setColor(red, green, blue, alpha);
    }

    public static void close() {
        // WorldOverlayBuffer owns the shared 26.2 GPU staging lifecycle.
    }

    private static RenderPipeline registerPipeline(String name, PrimitiveTopology topology) {
        return GameAccess.registerPipeline(
                RenderPipeline.builder(GameAccess.debugFilledSnippet())
                        .withLocation(Identifier.fromNamespaceAndPath(MoonsConfig.MOD_ID, name))
                        .withVertexBinding(0, DefaultVertexFormat.POSITION_COLOR)
                        .withPrimitiveTopology(topology)
                        .withDepthStencilState(Optional.empty())
                        .build());
    }

    private static int alpha(int argb) {
        return argb >>> 24 & 0xFF;
    }
}
