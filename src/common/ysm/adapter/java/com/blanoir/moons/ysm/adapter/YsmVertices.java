package com.blanoir.moons.ysm.adapter;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import org.joml.Vector3f;

/** Shared vertex submission for body, first-person arms and model sub-entities. */
final class YsmVertices {
    static void emit(
            PoseStack.Pose transform,
            VertexConsumer consumer,
            float[] vertices,
            int overlay,
            int light) {
        var position = new Vector3f();
        var normal = new Vector3f();
        var matrix = transform.pose();
        // Mesh emits flat-shaded quads: all four vertices share the same transformed normal.
        for (int face = 0; face < vertices.length; face += 32) {
            transform.transformNormal(
                    vertices[face + 5], vertices[face + 6], vertices[face + 7], normal);
            for (int i = face; i < face + 32; i += 8) {
                matrix.transformPosition(vertices[i], vertices[i + 1], vertices[i + 2], position);
                // BufferBuilder overrides this complete-vertex call with its packed fast path.
                consumer.addVertex(
                        position.x,
                        position.y,
                        position.z,
                        -1,
                        vertices[i + 3],
                        vertices[i + 4],
                        overlay,
                        light,
                        normal.x,
                        normal.y,
                        normal.z);
            }
        }
    }
}
