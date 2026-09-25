package com.blanoir.moons.client.render.world;

import com.blanoir.moons.client.render.WorldOverlayRenderer.ColoredBox;
import com.blanoir.moons.client.render.WorldOverlayRenderer.ColoredPin;
import com.mojang.blaze3d.vertex.VertexConsumer;

import org.joml.Matrix4fc;

/** Frozen pre-optimization geometry, used only for exact vertex/color parity checks. */
public final class ReferenceOverlayGeometry {
    private ReferenceOverlayGeometry() {}

    /** Emit pairs for DEBUG_LINES, not LINES (which Minecraft expands into shader quads).
     * Hardware clipping and a fixed one-pixel raster width avoid twisting at the camera plane. */
    public static void renderOutlineBox(Matrix4fc pose, VertexConsumer b, ColoredBox box) {
        float r = box.red(), g = box.green(), blue = box.blue();
        for (int i = 0; i < 4; i++) {
            float x = (i & 1) == 0 ? box.minX() : box.maxX();
            float y = (i & 2) == 0 ? box.minY() : box.maxY();
            vertex(b, pose, x, y, box.minZ(), r, g, blue, 1);
            vertex(b, pose, x, y, box.maxZ(), r, g, blue, 1);
            float z = (i & 1) == 0 ? box.minZ() : box.maxZ();
            vertex(b, pose, box.minX(), y, z, r, g, blue, 1);
            vertex(b, pose, box.maxX(), y, z, r, g, blue, 1);
            x = (i & 2) == 0 ? box.minX() : box.maxX();
            vertex(b, pose, x, box.minY(), z, r, g, blue, 1);
            vertex(b, pose, x, box.maxY(), z, r, g, blue, 1);
        }
    }

    public static void renderSoftFill(Matrix4fc pose, VertexConsumer b, ColoredBox box) {
        renderFilledBox(pose, b, box, .18f);
    }

    public static void renderPin(Matrix4fc pose, VertexConsumer builder, ColoredPin pin) {
        float halfWidth = pin.width() / 2.0f;
        float stemTop = pin.y() + pin.height();
        float capHalfWidth = halfWidth * 1.9f;
        float capHeight = Math.max(pin.width() * 1.6f, 0.05f);
        renderFilledBox(
                pose,
                builder,
                new ColoredBox(
                        pin.x() - halfWidth,
                        pin.y(),
                        pin.z() - halfWidth,
                        pin.x() + halfWidth,
                        stemTop,
                        pin.z() + halfWidth,
                        pin.red(),
                        pin.green(),
                        pin.blue(),
                        pin.alpha()));
        renderFilledBox(
                pose,
                builder,
                new ColoredBox(
                        pin.x() - capHalfWidth,
                        stemTop - capHeight,
                        pin.z() - capHalfWidth,
                        pin.x() + capHalfWidth,
                        stemTop,
                        pin.z() + capHalfWidth,
                        pin.red(),
                        pin.green(),
                        pin.blue(),
                        Math.min(1.0f, pin.alpha() + 0.12f)));
    }

    public static void renderFilledBox(Matrix4fc pose, VertexConsumer b, ColoredBox box) {
        renderFilledBox(pose, b, box, 1);
    }

    private static void renderFilledBox(
            Matrix4fc pose, VertexConsumer b, ColoredBox box, float opacity) {
        float x1 = box.minX(), y1 = box.minY(), z1 = box.minZ();
        float x2 = box.maxX(), y2 = box.maxY(), z2 = box.maxZ();
        float r = box.red(), g = box.green(), blue = box.blue(), a = box.alpha() * opacity;

        vertex(b, pose, x1, y1, z2, r, g, blue, a);
        vertex(b, pose, x2, y1, z2, r, g, blue, a);
        vertex(b, pose, x2, y2, z2, r, g, blue, a);
        vertex(b, pose, x1, y2, z2, r, g, blue, a);
        vertex(b, pose, x2, y1, z1, r, g, blue, a);
        vertex(b, pose, x1, y1, z1, r, g, blue, a);
        vertex(b, pose, x1, y2, z1, r, g, blue, a);
        vertex(b, pose, x2, y2, z1, r, g, blue, a);
        vertex(b, pose, x1, y1, z1, r, g, blue, a);
        vertex(b, pose, x1, y1, z2, r, g, blue, a);
        vertex(b, pose, x1, y2, z2, r, g, blue, a);
        vertex(b, pose, x1, y2, z1, r, g, blue, a);
        vertex(b, pose, x2, y1, z2, r, g, blue, a);
        vertex(b, pose, x2, y1, z1, r, g, blue, a);
        vertex(b, pose, x2, y2, z1, r, g, blue, a);
        vertex(b, pose, x2, y2, z2, r, g, blue, a);
        vertex(b, pose, x1, y2, z2, r, g, blue, a);
        vertex(b, pose, x2, y2, z2, r, g, blue, a);
        vertex(b, pose, x2, y2, z1, r, g, blue, a);
        vertex(b, pose, x1, y2, z1, r, g, blue, a);
        vertex(b, pose, x1, y1, z1, r, g, blue, a);
        vertex(b, pose, x2, y1, z1, r, g, blue, a);
        vertex(b, pose, x2, y1, z2, r, g, blue, a);
        vertex(b, pose, x1, y1, z2, r, g, blue, a);
    }

    private static void vertex(
            VertexConsumer builder,
            Matrix4fc pose,
            float x,
            float y,
            float z,
            float red,
            float green,
            float blue,
            float alpha) {
        builder.addVertex(pose, x, y, z).setColor(red, green, blue, alpha);
    }
}
