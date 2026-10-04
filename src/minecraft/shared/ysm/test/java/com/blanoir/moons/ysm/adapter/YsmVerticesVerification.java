package com.blanoir.moons.ysm.adapter;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;

/** Tests emitted positions, UVs and unit normals without a GPU context. */
final class YsmVerticesVerification {
    static void verify() {
        float[] vertices = {
            1, 2, 3, .2f, .8f, 0, 1, 0, 2, 2, 3, .7f, .8f, 0, 1, 0, 2, 2, 4, .7f, .1f, 0, 1, 0, 1,
            2, 4, .2f, .1f, 0, 1, 0
        };
        for (float[] scale : new float[][] {{1, 1, 1}, {-1, -1, 1}, {.7f, 2, .4f}}) {
            Matrix4f pose =
                    new Matrix4f()
                            .translate(.3f, -2, 7)
                            .rotateXYZ(.2f, -.6f, .1f)
                            .scale(scale[0], scale[1], scale[2]);
            var actual = new ArrayList<float[]>();
            YsmVertices.emit(
                    pose,
                    vertices,
                    (x, y, z, u, v, nx, ny, nz) ->
                            actual.add(new float[] {x, y, z, u, v, nx, ny, nz}));
            if (actual.size() != 4) throw new AssertionError("Vertex count");
            for (int i = 0; i < 4; i++) {
                float[] a = actual.get(i);
                Vector3f expected =
                        new Vector3f(vertices[i * 8], vertices[i * 8 + 1], vertices[i * 8 + 2])
                                .mulPosition(pose);
                check(a[0], expected.x);
                check(a[1], expected.y);
                check(a[2], expected.z);
                check(a[3], vertices[i * 8 + 3]);
                check(a[4], vertices[i * 8 + 4]);
                Vector3f normal = new Vector3f(a[5], a[6], a[7]);
                check(normal.length(), 1);
                Vector3f tangentX = new Vector3f(1, 0, 0).mulDirection(pose);
                Vector3f tangentZ = new Vector3f(0, 0, 1).mulDirection(pose);
                check(normal.dot(tangentX), 0);
                check(normal.dot(tangentZ), 0);
                if (!Float.isFinite(normal.x + normal.y + normal.z))
                    throw new AssertionError("Non-finite normal");
            }
        }
        boolean rejected = false;
        try {
            YsmVertices.emit(new Matrix4f(), new float[8], (x, y, z, u, v, nx, ny, nz) -> {});
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        if (!rejected) throw new AssertionError("Incomplete quad accepted");
        System.out.println(
                "YSM_VERTICES_VERIFIED transforms=rotation+reflection+nonuniform-scale uv=preserved normals=unit-and-perpendicular");
    }

    private static void check(float a, float b) {
        if (Math.abs(a - b) > 1e-5f) throw new AssertionError(a + " != " + b);
    }
}
