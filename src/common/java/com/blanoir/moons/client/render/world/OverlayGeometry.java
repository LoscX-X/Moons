package com.blanoir.moons.client.render.world;

import com.blanoir.moons.client.render.WorldOverlayRenderer.ColoredBox;
import com.blanoir.moons.client.render.WorldOverlayRenderer.ColoredPin;
import com.mojang.blaze3d.vertex.VertexConsumer;

import org.joml.Matrix4fc;
import org.joml.Vector3f;

/** Shared independent quad geometry; pipeline, buffers, and lifetime stay version-owned. */
public final class OverlayGeometry {
    private OverlayGeometry() {}

    /** Emit pairs for DEBUG_LINES, not LINES (which Minecraft expands into shader quads).
     * Hardware clipping and a fixed one-pixel raster width avoid twisting at the camera plane. */
    public static void renderOutlineBox(Matrix4fc pose, VertexConsumer b, ColoredBox box) {
        float r = box.red(), g = box.green(), blue = box.blue();
        Vector3f point = new Vector3f();
        pose.transformPosition(box.minX(), box.minY(), box.minZ(), point);
        float x000 = point.x, y000 = point.y, z000 = point.z;
        pose.transformPosition(box.minX(), box.minY(), box.maxZ(), point);
        float x001 = point.x, y001 = point.y, z001 = point.z;
        pose.transformPosition(box.minX(), box.maxY(), box.minZ(), point);
        float x010 = point.x, y010 = point.y, z010 = point.z;
        pose.transformPosition(box.minX(), box.maxY(), box.maxZ(), point);
        float x011 = point.x, y011 = point.y, z011 = point.z;
        pose.transformPosition(box.maxX(), box.minY(), box.minZ(), point);
        float x100 = point.x, y100 = point.y, z100 = point.z;
        pose.transformPosition(box.maxX(), box.minY(), box.maxZ(), point);
        float x101 = point.x, y101 = point.y, z101 = point.z;
        pose.transformPosition(box.maxX(), box.maxY(), box.minZ(), point);
        float x110 = point.x, y110 = point.y, z110 = point.z;
        pose.transformPosition(box.maxX(), box.maxY(), box.maxZ(), point);
        float x111 = point.x, y111 = point.y, z111 = point.z;
        vertex(b, x000, y000, z000, r, g, blue, 1);
        vertex(b, x001, y001, z001, r, g, blue, 1);
        vertex(b, x000, y000, z000, r, g, blue, 1);
        vertex(b, x100, y100, z100, r, g, blue, 1);
        vertex(b, x000, y000, z000, r, g, blue, 1);
        vertex(b, x010, y010, z010, r, g, blue, 1);
        vertex(b, x100, y100, z100, r, g, blue, 1);
        vertex(b, x101, y101, z101, r, g, blue, 1);
        vertex(b, x001, y001, z001, r, g, blue, 1);
        vertex(b, x101, y101, z101, r, g, blue, 1);
        vertex(b, x001, y001, z001, r, g, blue, 1);
        vertex(b, x011, y011, z011, r, g, blue, 1);
        vertex(b, x010, y010, z010, r, g, blue, 1);
        vertex(b, x011, y011, z011, r, g, blue, 1);
        vertex(b, x010, y010, z010, r, g, blue, 1);
        vertex(b, x110, y110, z110, r, g, blue, 1);
        vertex(b, x100, y100, z100, r, g, blue, 1);
        vertex(b, x110, y110, z110, r, g, blue, 1);
        vertex(b, x110, y110, z110, r, g, blue, 1);
        vertex(b, x111, y111, z111, r, g, blue, 1);
        vertex(b, x011, y011, z011, r, g, blue, 1);
        vertex(b, x111, y111, z111, r, g, blue, 1);
        vertex(b, x101, y101, z101, r, g, blue, 1);
        vertex(b, x111, y111, z111, r, g, blue, 1);
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
                pin.x() - halfWidth,
                pin.y(),
                pin.z() - halfWidth,
                pin.x() + halfWidth,
                stemTop,
                pin.z() + halfWidth,
                pin.red(),
                pin.green(),
                pin.blue(),
                pin.alpha());
        renderFilledBox(
                pose,
                builder,
                pin.x() - capHalfWidth,
                stemTop - capHeight,
                pin.z() - capHalfWidth,
                pin.x() + capHalfWidth,
                stemTop,
                pin.z() + capHalfWidth,
                pin.red(),
                pin.green(),
                pin.blue(),
                Math.min(1.0f, pin.alpha() + 0.12f));
    }

    public static void renderFilledBox(Matrix4fc pose, VertexConsumer b, ColoredBox box) {
        renderFilledBox(pose, b, box, 1);
    }

    private static void renderFilledBox(
            Matrix4fc pose, VertexConsumer b, ColoredBox box, float opacity) {
        renderFilledBox(
                pose,
                b,
                box.minX(),
                box.minY(),
                box.minZ(),
                box.maxX(),
                box.maxY(),
                box.maxZ(),
                box.red(),
                box.green(),
                box.blue(),
                box.alpha() * opacity);
    }

    private static void renderFilledBox(
            Matrix4fc pose,
            VertexConsumer b,
            float x1,
            float y1,
            float z1,
            float x2,
            float y2,
            float z2,
            float r,
            float g,
            float blue,
            float a) {
        // Each corner belongs to three faces. Transform it once using VertexConsumer's
        // exact transform operation, while retaining face/vertex order and winding.
        Vector3f point = new Vector3f();
        pose.transformPosition(x1, y1, z1, point);
        float x000 = point.x, y000 = point.y, z000 = point.z;
        pose.transformPosition(x1, y1, z2, point);
        float x001 = point.x, y001 = point.y, z001 = point.z;
        pose.transformPosition(x1, y2, z1, point);
        float x010 = point.x, y010 = point.y, z010 = point.z;
        pose.transformPosition(x1, y2, z2, point);
        float x011 = point.x, y011 = point.y, z011 = point.z;
        pose.transformPosition(x2, y1, z1, point);
        float x100 = point.x, y100 = point.y, z100 = point.z;
        pose.transformPosition(x2, y1, z2, point);
        float x101 = point.x, y101 = point.y, z101 = point.z;
        pose.transformPosition(x2, y2, z1, point);
        float x110 = point.x, y110 = point.y, z110 = point.z;
        pose.transformPosition(x2, y2, z2, point);
        float x111 = point.x, y111 = point.y, z111 = point.z;

        vertex(b, x001, y001, z001, r, g, blue, a);
        vertex(b, x101, y101, z101, r, g, blue, a);
        vertex(b, x111, y111, z111, r, g, blue, a);
        vertex(b, x011, y011, z011, r, g, blue, a);
        vertex(b, x100, y100, z100, r, g, blue, a);
        vertex(b, x000, y000, z000, r, g, blue, a);
        vertex(b, x010, y010, z010, r, g, blue, a);
        vertex(b, x110, y110, z110, r, g, blue, a);
        vertex(b, x000, y000, z000, r, g, blue, a);
        vertex(b, x001, y001, z001, r, g, blue, a);
        vertex(b, x011, y011, z011, r, g, blue, a);
        vertex(b, x010, y010, z010, r, g, blue, a);
        vertex(b, x101, y101, z101, r, g, blue, a);
        vertex(b, x100, y100, z100, r, g, blue, a);
        vertex(b, x110, y110, z110, r, g, blue, a);
        vertex(b, x111, y111, z111, r, g, blue, a);
        vertex(b, x011, y011, z011, r, g, blue, a);
        vertex(b, x111, y111, z111, r, g, blue, a);
        vertex(b, x110, y110, z110, r, g, blue, a);
        vertex(b, x010, y010, z010, r, g, blue, a);
        vertex(b, x000, y000, z000, r, g, blue, a);
        vertex(b, x100, y100, z100, r, g, blue, a);
        vertex(b, x101, y101, z101, r, g, blue, a);
        vertex(b, x001, y001, z001, r, g, blue, a);
    }

    private static void vertex(
            VertexConsumer b, float x, float y, float z, float r, float g, float blue, float a) {
        b.addVertex(x, y, z).setColor(r, g, blue, a);
    }
}
