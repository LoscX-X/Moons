package com.blanoir.moons.client.render;

import com.blanoir.moons.client.access.GameAccess;
import com.blanoir.moons.client.config.MoonsConfig;
import com.blanoir.moons.client.render.world.OverlayGeometry;
import com.blanoir.moons.client.render.world.WorldOverlayBuffer;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

import org.joml.Matrix4fc;

import java.util.List;
import java.util.Optional;

public final class WorldOverlayRenderer {
    private static final RenderPipeline THROUGH_WALLS =
            GameAccess.registerPipeline(
                    RenderPipeline.builder(GameAccess.debugFilledSnippet())
                            .withLocation(
                                    Identifier.fromNamespaceAndPath(
                                            MoonsConfig.MOD_ID,
                                            "pipeline/world_box_highlight_through_walls"))
                            // The builder below emits six independent quad faces per box.
                            // Do not inherit DEBUG_FILLED_SNIPPET's connected strip mode:
                            // it joins separate UhcFinder entities with giant polygons.
                            .withVertexBinding(0, DefaultVertexFormat.POSITION_COLOR)
                            .withPrimitiveTopology(PrimitiveTopology.QUADS)
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

    public static void close() {
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
