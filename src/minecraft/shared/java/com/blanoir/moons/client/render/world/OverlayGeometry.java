package com.blanoir.moons.client.render.world;

import com.blanoir.moons.client.render.WorldOverlayRenderer.ColoredBox;
import com.blanoir.moons.client.render.WorldOverlayRenderer.ColoredPin;
import com.mojang.blaze3d.vertex.VertexConsumer;

import org.joml.Matrix4fc;

/** Shared independent quad geometry; pipeline, buffers, and lifetime stay version-owned. */
public final class OverlayGeometry {
    private OverlayGeometry() {}

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
        float x1 = box.minX(), y1 = box.minY(), z1 = box.minZ();
        float x2 = box.maxX(), y2 = box.maxY(), z2 = box.maxZ();
        float r = box.red(), g = box.green(), blue = box.blue(), a = box.alpha();

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
