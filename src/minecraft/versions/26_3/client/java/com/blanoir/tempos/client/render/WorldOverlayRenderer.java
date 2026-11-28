package com.blanoir.tempos.client.render;

import com.blanoir.tempos.client.access.GameAccess;
import com.blanoir.tempos.client.config.TemposConfig;
import com.blanoir.tempos.client.render.world.OverlayGeometry;
import com.blanoir.tempos.client.render.world.WorldOverlayBuffer;
import com.blanoir.tempos.client.render.world.WorldContourTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

import org.joml.Matrix4fc;
import org.joml.Vector4f;

import java.util.List;
import java.util.Optional;

public final class WorldOverlayRenderer {
    private static final WorldContourTarget CONTOUR = new WorldContourTarget(
            (width, height) -> new TextureTarget("Tempos Xray contour", width, height, GpuFormat.RGBA8_UNORM, null));
    private static final RenderPipeline THROUGH_WALLS =
            GameAccess.registerPipeline(
                    RenderPipeline.builder(GameAccess.debugFilledSnippet())
                            .withLocation(
                                    Identifier.fromNamespaceAndPath(
                                            TemposConfig.MOD_ID,
                                            "pipeline/world_box_highlight_through_walls"))
                            // The builder below emits six independent quad faces per box.
                            // Do not inherit DEBUG_FILLED_SNIPPET's connected strip mode:
                            // it joins separate UhcFinder entities with giant polygons.
                            .withVertexBinding(0, DefaultVertexFormat.POSITION_COLOR)
                            .withPrimitiveTopology(PrimitiveTopology.QUADS)
                            .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
                            .withDepthStencilState(Optional.empty())
                            .build());

    private static final RenderPipeline OUTLINES =
            GameAccess.registerPipeline(
                    RenderPipeline.builder(GameAccess.debugFilledSnippet())
                            .withLocation(
                                    Identifier.fromNamespaceAndPath(
                                            TemposConfig.MOD_ID, "pipeline/world_box_outline"))
                            .withVertexBinding(0, DefaultVertexFormat.POSITION_COLOR)
                            .withPrimitiveTopology(PrimitiveTopology.DEBUG_LINES)
                            .withCull(false)
                            .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
                            .withDepthStencilState(Optional.empty())
                            .build());

    private WorldOverlayRenderer() {}

    public static void render(
            Minecraft client, PoseStack matrices, List<ColoredBox> boxes, String label) {
        if (client == null || boxes.isEmpty()) {
            return;
        }
        Matrix4fc pose = matrices.last().pose();
        WorldOverlayBuffer.draw(
                client,
                THROUGH_WALLS,
                label,
                builder -> {
                    for (ColoredBox box : boxes) {
                        renderFilledBox(pose, builder, box);
                    }
                });
    }

    public static void renderPins(
            Minecraft client, PoseStack matrices, List<ColoredPin> pins, String label) {
        if (client == null || pins.isEmpty()) {
            return;
        }
        Matrix4fc pose = matrices.last().pose();
        WorldOverlayBuffer.draw(
                client,
                THROUGH_WALLS,
                label,
                builder -> {
                    for (ColoredPin pin : pins) {
                        renderPin(pose, builder, pin);
                    }
                });
    }

    public static void renderStyled(
            Minecraft client, PoseStack matrices, List<ColoredBox> values, String label) {
        if (client == null || values.isEmpty()) return;
        Matrix4fc pose = matrices.last().pose();
        // A quiet, unsorted fill batch followed by one opaque line batch. No per-edge quads.
        WorldOverlayBuffer.drawUnsorted(
                client,
                THROUGH_WALLS,
                label,
                builder -> {
                    for (ColoredBox value : values)
                        OverlayGeometry.renderSoftFill(pose, builder, value);
                });
        WorldOverlayBuffer.drawUnsorted(
                client,
                OUTLINES,
                label,
                builder -> {
                    for (ColoredBox value : values)
                        OverlayGeometry.renderOutlineBox(pose, builder, value);
                });
    }

    /** Extract only the boundary of the complete projected mask, including overlapping groups. */
    public static void renderConnected(Minecraft client, PoseStack matrices,
            List<com.blanoir.tempos.client.render.world.BlockUnionMesh.Colored> values, String label) {
        if (client == null || values.isEmpty()) return;
        var mask = CONTOUR.begin(client);
        if (mask == null) return;
        RenderSystem.getDevice().createCommandEncoder()
                .clearColorTexture(mask.getColorTexture(), new Vector4f(0, 0, 0, 0));
        Matrix4fc pose = matrices.last().pose();
        WorldOverlayBuffer.drawTo(mask, THROUGH_WALLS, label + " silhouette mask", builder -> {
            for (var value : values) value.mesh().fill(pose, builder, value.red(), value.green(), value.blue(), 1);
        });
        if (CONTOUR.composite(client)) return;
        // Keep highlights available if a resource pack removes the built-in outline effect.
        WorldOverlayBuffer.drawUnsorted(client, OUTLINES, label + " outline", builder -> {
            for (var value : values) value.mesh().outline(pose, builder, value.red(), value.green(), value.blue());
        });
    }

    public static void close() {
        CONTOUR.close();
        VisualRenderTargets.close();
        WorldOverlayBuffer.close();
    }

    private static void renderPin(Matrix4fc pose, VertexConsumer builder, ColoredPin pin) {
        OverlayGeometry.renderPin(pose, builder, pin);
    }

    private static void renderFilledBox(Matrix4fc pose, VertexConsumer builder, ColoredBox box) {
        OverlayGeometry.renderFilledBox(pose, builder, box);
    }

    public record ColoredBox(
            float minX,
            float minY,
            float minZ,
            float maxX,
            float maxY,
            float maxZ,
            float red,
            float green,
            float blue,
            float alpha) {}

    public record ColoredPin(
            float x,
            float y,
            float z,
            float height,
            float width,
            float red,
            float green,
            float blue,
            float alpha) {}
}
