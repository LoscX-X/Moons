package com.blanoir.moons.ysm;

import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.Arrays;
import java.util.Random;

/** Frozen scalar transform oracle verifies deduplication without tolerating numerical drift. */
final class YsmMeshBatchVerification {
    static void verify() {
        Random random = new Random(20260925);
        for (int sample = 0; sample < 300; sample++) {
            float[] source = new float[(1 + random.nextInt(100)) * 32];
            for (int i = 0; i < source.length; i += 8) {
                // Shared corners with different UVs, signed zero and some unshared positions.
                source[i] = sample % 2 == 0 ? random.nextInt(3) : random.nextFloat();
                source[i + 1] = i % 3 == 0 ? -0.0f : random.nextInt(3);
                source[i + 2] = random.nextInt(3);
                source[i + 3] = random.nextFloat();
                source[i + 4] = random.nextFloat();
                source[i + 5] = sample % 17 == 0 ? Float.NaN : random.nextInt(3) - 1;
                source[i + 6] = sample % 19 == 0 ? Float.POSITIVE_INFINITY : random.nextInt(3) - 1;
                source[i + 7] = random.nextInt(3) - 1;
            }
            YsmMeshBatch batch = new YsmMeshBatch(sample % 8, source);
            float[] saved = null;
            float[] retained = null;
            for (int frame = 0; frame < 5; frame++) {
                Matrix4f matrix =
                        new Matrix4f()
                                .translate(
                                        random.nextFloat(), random.nextFloat(), random.nextFloat())
                                .rotateXYZ(
                                        random.nextFloat(), random.nextFloat(), random.nextFloat())
                                .scale(
                                        sample % 7 == 0 ? 0 : random.nextFloat() * 3 - 1,
                                        random.nextFloat() * 2,
                                        random.nextFloat() * -2);
                Matrix3f normal = matrix.normal(new Matrix3f());
                float[] expected = new float[source.length + 10],
                        actual = new float[expected.length];
                Arrays.fill(expected, 1234);
                Arrays.fill(actual, 1234);
                reference(source, matrix, normal, expected, 5);
                batch.transform(matrix, normal, actual, 5);
                for (int i = 0; i < actual.length; i++)
                    if (Float.floatToRawIntBits(expected[i]) != Float.floatToRawIntBits(actual[i]))
                        throw new AssertionError(
                                "YSM vertex/UV/normal bits changed at " + sample + ":" + i);
                if (saved != null && !Arrays.equals(saved, retained))
                    throw new AssertionError("Prior output modified");
                saved = actual;
                retained = actual.clone();
            }
        }
        float[] invalid = new float[32];
        invalid[0] = Float.POSITIVE_INFINITY;
        try {
            new YsmMeshBatch(0, invalid)
                    .transform(new Matrix4f(), new Matrix3f(), new float[32], 0);
            throw new AssertionError("Nonfinite position accepted");
        } catch (IllegalArgumentException expected) {
        }
        System.out.println(
                "YSM_MESH_BATCH_VERIFIED cases=1500 positions+uv+normals=bit-exact signed-zero+normal-fallback+nonfinite=preserved");
    }

    private static volatile float benchmarkSink;

    static void benchmark() {
        Random random = new Random(20260925);
        for (int cubes : new int[] {1, 16, 128}) {
            for (boolean shared : new boolean[] {true, false}) {
                float[] source = new float[cubes * 24 * 8];
                int[][] faces = {
                    {0, 1, 3, 2},
                    {4, 6, 7, 5},
                    {0, 4, 5, 1},
                    {2, 3, 7, 6},
                    {0, 2, 6, 4},
                    {1, 5, 7, 3}
                };
                for (int cube = 0; cube < cubes; cube++) {
                    for (int face = 0; face < 6; face++) {
                        for (int vertex = 0; vertex < 4; vertex++) {
                            int at = ((cube * 6 + face) * 4 + vertex) * 8;
                            int corner = faces[face][vertex];
                            source[at] = shared ? cube * 3 + (corner & 1) : random.nextFloat();
                            source[at + 1] = shared ? (corner >> 1) & 1 : random.nextFloat();
                            source[at + 2] = shared ? (corner >> 2) & 1 : random.nextFloat();
                            source[at + 3] = random.nextFloat();
                            source[at + 4] = random.nextFloat();
                            source[at + 5 + face / 2] = (face & 1) == 0 ? -1 : 1;
                        }
                    }
                }
                YsmMeshBatch batch = new YsmMeshBatch(0, source);
                float[] output = new float[source.length];
                Matrix4f matrix = new Matrix4f().rotateXYZ(.3f, -.5f, .2f).scale(1, 2, -3);
                Matrix3f normal = matrix.normal(new Matrix3f());
                int iterations = Math.max(3000, 300000 / cubes);
                double[][] timings = new double[2][5];
                // Pair the implementations and alternate which runs first to reduce phase bias.
                for (int sample = -2; sample < 5; sample++) {
                    for (int order = 0; order < 2; order++) {
                        int variant = (sample + 2 + order) & 1;
                        long start = System.nanoTime();
                        for (int i = 0; i < iterations; i++) {
                            matrix.m30(i * .001f);
                            if (variant == 1) batch.transform(matrix, normal, output, 0);
                            else reference(source, matrix, normal, output, 0);
                            benchmarkSink = output[(i % (source.length / 8)) * 8];
                        }
                        if (sample >= 0)
                            timings[variant][sample] =
                                    (System.nanoTime() - start) / (iterations * 1000d);
                    }
                }
                for (int variant = 0; variant < 2; variant++) {
                    double[] samples = timings[variant];
                    Arrays.sort(samples);
                    System.out.printf(
                            java.util.Locale.ROOT,
                            "YSM_TRANSFORM_BENCHMARK vertices=%d shared=%s optimized=%s median-us=%.3f samples=5%n",
                            source.length / 8,
                            shared,
                            variant == 1,
                            samples[2]);
                }
            }
        }
    }

    static void reference(
            float[] source, Matrix4f matrix, Matrix3f normalMatrix, float[] output, int offset) {
        Vector3f position = new Vector3f(), normal = new Vector3f();
        for (int face = 0; face < source.length; face += 32) {
            normal.set(source[face + 5], source[face + 6], source[face + 7]).mul(normalMatrix);
            if (normal.isFinite() && normal.lengthSquared() > 1e-12f) normal.normalize();
            else normal.set(0, 1, 0);
            for (int v = face; v < face + 32; v += 8) {
                position.set(source[v], source[v + 1], source[v + 2]).mulPosition(matrix);
                if (!position.isFinite())
                    throw new IllegalArgumentException("Non-finite model vertex");
                output[offset++] = position.x;
                output[offset++] = position.y;
                output[offset++] = position.z;
                output[offset++] = source[v + 3];
                output[offset++] = source[v + 4];
                output[offset++] = normal.x;
                output[offset++] = normal.y;
                output[offset++] = normal.z;
            }
        }
    }
}
