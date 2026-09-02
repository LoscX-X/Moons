package com.blanoir.moons.client.render;

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
import org.joml.Matrix4fc;

import java.util.List;
import java.util.Optional;

public final class WorldOverlayRenderer {
    private static final RenderPipeline THROUGH_WALLS = GameAccess.registerPipeline(
            RenderPipeline.builder(GameAccess.debugFilledSnippet())
                    .withLocation(Identifier.fromNamespaceAndPath(
                            MoonsConfig.MOD_ID,
                            "pipeline/world_box_highlight_through_walls"
                    ))
                    // The builder below emits six independent quad faces per box.
                    // Do not inherit DEBUG_FILLED_SNIPPET's connected strip mode:
                    // it joins separate UhcFinder entities with giant polygons.
                    .withVertexBinding(0, DefaultVertexFormat.POSITION_COLOR)
                    .withPrimitiveTopology(PrimitiveTopology.QUADS)
                    .withDepthStencilState(Optional.empty())
                    .build()
    );

    private WorldOverlayRenderer() {
    }

    public static void render(Minecraft client, PoseStack matrices, List<ColoredBox> boxes, String label) {
        if (client == null || boxes.isEmpty()) {
            return;
        }
        Matrix4fc pose = matrices.last().pose();
        WorldOverlayBuffer.draw(client, THROUGH_WALLS, label, builder -> {
            for (ColoredBox box : boxes) {
                renderFilledBox(pose, builder, box);
            }
        });
    }

    public static void renderPins(Minecraft client, PoseStack matrices, List<ColoredPin> pins, String label) {
        if (client == null || pins.isEmpty()) {
            return;
        }
        Matrix4fc pose = matrices.last().pose();
        WorldOverlayBuffer.draw(client, THROUGH_WALLS, label, builder -> {
            for (ColoredPin pin : pins) {
                renderPin(pose, builder, pin);
            }
        });
    }

    public static void close() {
        WorldOverlayBuffer.close();
    }

    private static void renderPin(Matrix4fc pose, VertexConsumer builder, ColoredPin pin) {
        float halfWidth = pin.width() / 2.0f;
        float stemTop = pin.y() + pin.height();
        float capHalfWidth = halfWidth * 1.9f;
        float capHeight = Math.max(pin.width() * 1.6f, 0.05f);
        renderFilledBox(pose, builder, new ColoredBox(
                pin.x() - halfWidth, pin.y(), pin.z() - halfWidth,
                pin.x() + halfWidth, stemTop, pin.z() + halfWidth,
                pin.red(), pin.green(), pin.blue(), pin.alpha()
        ));
        renderFilledBox(pose, builder, new ColoredBox(
                pin.x() - capHalfWidth, stemTop - capHeight, pin.z() - capHalfWidth,
                pin.x() + capHalfWidth, stemTop, pin.z() + capHalfWidth,
                pin.red(), pin.green(), pin.blue(), Math.min(1.0f, pin.alpha() + 0.12f)
        ));
    }

    private static void renderFilledBox(Matrix4fc pose, VertexConsumer b, ColoredBox box) {
        float x1 = box.minX(), y1 = box.minY(), z1 = box.minZ();
        float x2 = box.maxX(), y2 = box.maxY(), z2 = box.maxZ();
        float r = box.red(), g = box.green(), blue = box.blue(), a = box.alpha();

        vertex(b, pose, x1, y1, z2, r, g, blue, a); vertex(b, pose, x2, y1, z2, r, g, blue, a);
        vertex(b, pose, x2, y2, z2, r, g, blue, a); vertex(b, pose, x1, y2, z2, r, g, blue, a);
        vertex(b, pose, x2, y1, z1, r, g, blue, a); vertex(b, pose, x1, y1, z1, r, g, blue, a);
        vertex(b, pose, x1, y2, z1, r, g, blue, a); vertex(b, pose, x2, y2, z1, r, g, blue, a);
        vertex(b, pose, x1, y1, z1, r, g, blue, a); vertex(b, pose, x1, y1, z2, r, g, blue, a);
        vertex(b, pose, x1, y2, z2, r, g, blue, a); vertex(b, pose, x1, y2, z1, r, g, blue, a);
        vertex(b, pose, x2, y1, z2, r, g, blue, a); vertex(b, pose, x2, y1, z1, r, g, blue, a);
        vertex(b, pose, x2, y2, z1, r, g, blue, a); vertex(b, pose, x2, y2, z2, r, g, blue, a);
        vertex(b, pose, x1, y2, z2, r, g, blue, a); vertex(b, pose, x2, y2, z2, r, g, blue, a);
        vertex(b, pose, x2, y2, z1, r, g, blue, a); vertex(b, pose, x1, y2, z1, r, g, blue, a);
        vertex(b, pose, x1, y1, z1, r, g, blue, a); vertex(b, pose, x2, y1, z1, r, g, blue, a);
        vertex(b, pose, x2, y1, z2, r, g, blue, a); vertex(b, pose, x1, y1, z2, r, g, blue, a);
    }

    private static void vertex(VertexConsumer builder, Matrix4fc pose, float x, float y, float z,
                               float red, float green, float blue, float alpha) {
        builder.addVertex(pose, x, y, z).setColor(red, green, blue, alpha);
    }

    public record ColoredBox(float minX, float minY, float minZ, float maxX, float maxY, float maxZ,
                             float red, float green, float blue, float alpha) {
    }

    public record ColoredPin(float x, float y, float z, float height, float width,
                             float red, float green, float blue, float alpha) {
    }
}
