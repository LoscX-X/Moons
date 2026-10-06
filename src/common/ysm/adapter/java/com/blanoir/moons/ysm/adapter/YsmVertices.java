package com.blanoir.moons.ysm.adapter;

import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;

/** Authored flat-shaded quad mesh submitted through the 1.8 fixed pipeline. */
final class YsmVertices {
    @FunctionalInterface
    interface Sink {
        void vertex(float x, float y, float z, float u, float v, float nx, float ny, float nz);
    }

    private static final Sink GL =
            (x, y, z, u, v, nx, ny, nz) -> {
                GL11.glNormal3f(nx, ny, nz);
                GL11.glTexCoord2f(u, v);
                GL11.glVertex3f(x, y, z);
            };

    static void emit(Matrix4f matrix, float[] vertices) {
        GL11.glBegin(GL11.GL_QUADS);
        try {
            emit(matrix, vertices, GL);
        } finally {
            GL11.glEnd();
        }
    }

    static void emit(Matrix4f matrix, float[] vertices, Sink sink) {
        if (vertices.length % 32 != 0)
            throw new IllegalArgumentException("YSM mesh must contain complete quads");
        Vector3f position = new Vector3f(), normal = new Vector3f();
        Matrix3f normals = matrix.normal(new Matrix3f());
        for (int face = 0; face < vertices.length; face += 32) {
            normals.transform(vertices[face + 5], vertices[face + 6], vertices[face + 7], normal)
                    .normalize();
            for (int i = face; i < face + 32; i += 8) {
                matrix.transformPosition(vertices[i], vertices[i + 1], vertices[i + 2], position);
                sink.vertex(
                        position.x,
                        position.y,
                        position.z,
                        vertices[i + 3],
                        vertices[i + 4],
                        normal.x,
                        normal.y,
                        normal.z);
            }
        }
    }
}
