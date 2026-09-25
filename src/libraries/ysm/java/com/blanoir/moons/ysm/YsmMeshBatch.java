package com.blanoir.moons.ysm;

import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Map;

/** Baked material vertices plus per-bone transform reuse; output remains an independent snapshot. */
final class YsmMeshBatch {
    final int material;
    final float[] vertices;
    float[] previousOutput;
    int previousOffset;
    private final int[] positionOffsets;
    private final int[] normalOffsets;
    private final float[] positions;
    private final float[] normals;
    private final float[] transformedPositions;
    private final float[] transformedNormals;
    private final Vector3f scratch = new Vector3f();

    YsmMeshBatch(int material, float[] vertices) {
        this.material = material;
        this.vertices = vertices;
        if (vertices.length < 32 * 8) {
            positionOffsets = normalOffsets = null;
            positions = normals = transformedPositions = transformedNormals = null;
            return;
        }
        int[] offsets = new int[vertices.length / 8];
        Map<VectorBits, Integer> positionIndex = new HashMap<>();
        for (int v = 0; v < vertices.length; v += 8)
            offsets[v / 8] = index(positionIndex, vertices, v);
        // Tiny or mostly unshared batches cost less to transform directly than to scatter.
        if (positionIndex.size() > offsets.length / 2) {
            positionOffsets = normalOffsets = null;
            positions = normals = transformedPositions = transformedNormals = null;
            return;
        }
        positionOffsets = offsets;
        normalOffsets = new int[vertices.length / 32];
        Map<VectorBits, Integer> normalIndex = new HashMap<>();
        for (int face = 0; face < vertices.length; face += 32)
            normalOffsets[face / 32] = index(normalIndex, vertices, face + 5);
        positions = coordinates(positionIndex);
        normals = coordinates(normalIndex);
        transformedPositions = new float[positions.length];
        transformedNormals = new float[normals.length];
    }

    private record VectorBits(int x, int y, int z) {}

    private static int index(Map<VectorBits, Integer> index, float[] values, int offset) {
        // Signed zero and NaN payloads must not be merged by approximate coordinate equality.
        var key =
                new VectorBits(
                        Float.floatToRawIntBits(values[offset]),
                        Float.floatToRawIntBits(values[offset + 1]),
                        Float.floatToRawIntBits(values[offset + 2]));
        return index.computeIfAbsent(key, unused -> index.size() * 3);
    }

    private static float[] coordinates(Map<VectorBits, Integer> index) {
        float[] result = new float[index.size() * 3];
        index.forEach(
                (bits, offset) -> {
                    result[offset] = Float.intBitsToFloat(bits.x);
                    result[offset + 1] = Float.intBitsToFloat(bits.y);
                    result[offset + 2] = Float.intBitsToFloat(bits.z);
                });
        return result;
    }

    void transform(Matrix4f matrix, Matrix3f normalMatrix, float[] output, int offset) {
        if (positionOffsets == null) {
            transformDirect(matrix, normalMatrix, output, offset);
            return;
        }
        for (int i = 0; i < positions.length; i += 3) {
            scratch.set(positions[i], positions[i + 1], positions[i + 2]).mulPosition(matrix);
            if (!scratch.isFinite()) throw new IllegalArgumentException("Non-finite model vertex");
            transformedPositions[i] = scratch.x;
            transformedPositions[i + 1] = scratch.y;
            transformedPositions[i + 2] = scratch.z;
        }
        for (int i = 0; i < normals.length; i += 3) {
            scratch.set(normals[i], normals[i + 1], normals[i + 2]).mul(normalMatrix);
            if (scratch.isFinite() && scratch.lengthSquared() > 1e-12f) scratch.normalize();
            else scratch.set(0, 1, 0);
            transformedNormals[i] = scratch.x;
            transformedNormals[i + 1] = scratch.y;
            transformedNormals[i + 2] = scratch.z;
        }
        for (int face = 0; face < vertices.length; face += 32) {
            int n = normalOffsets[face / 32];
            float nx = transformedNormals[n],
                    ny = transformedNormals[n + 1],
                    nz = transformedNormals[n + 2];
            for (int v = face; v < face + 32; v += 8) {
                int p = positionOffsets[v / 8];
                output[offset++] = transformedPositions[p];
                output[offset++] = transformedPositions[p + 1];
                output[offset++] = transformedPositions[p + 2];
                output[offset++] = vertices[v + 3];
                output[offset++] = vertices[v + 4];
                output[offset++] = nx;
                output[offset++] = ny;
                output[offset++] = nz;
            }
        }
    }

    private void transformDirect(
            Matrix4f matrix, Matrix3f normalMatrix, float[] output, int offset) {
        Vector3f position = new Vector3f(), normal = new Vector3f();
        for (int face = 0; face < vertices.length; face += 32) {
            normal.set(vertices[face + 5], vertices[face + 6], vertices[face + 7])
                    .mul(normalMatrix);
            if (normal.isFinite() && normal.lengthSquared() > 1e-12f) normal.normalize();
            else normal.set(0, 1, 0);
            for (int v = face; v < face + 32; v += 8) {
                position.set(vertices[v], vertices[v + 1], vertices[v + 2]).mulPosition(matrix);
                if (!position.isFinite())
                    throw new IllegalArgumentException("Non-finite model vertex");
                output[offset++] = position.x;
                output[offset++] = position.y;
                output[offset++] = position.z;
                output[offset++] = vertices[v + 3];
                output[offset++] = vertices[v + 4];
                output[offset++] = normal.x;
                output[offset++] = normal.y;
                output[offset++] = normal.z;
            }
        }
    }
}
