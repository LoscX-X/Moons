package com.blanoir.moons.client.utils.rotation.aim;

import com.blanoir.moons.client.module.impl.combat.silentaura.LearnedAimModel;
import com.blanoir.moons.client.render.WorldOverlayRenderer.ColoredBox;
import com.blanoir.moons.client.render.world.OverlayGeometry;
import com.blanoir.moons.client.render.world.ReferenceOverlayGeometry;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import org.joml.Matrix4f;

import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Locale;
import java.util.function.IntConsumer;
import java.util.function.Predicate;

/** CPU/allocation comparisons only; no GPU, world raycasting or FPS claims. */
public final class HotPathBenchmark {
    private static volatile Object sink;
    private static volatile float vertexSink;
    private static final com.sun.management.ThreadMXBean ALLOCATIONS = allocations();

    public static void main(String[] args) {
        AABB[] boxes = new AABB[32];
        ColoredBox[] colored = new ColoredBox[32];
        for (int i = 0; i < boxes.length; i++) {
            boxes[i] = new AABB(i * .01, 0, 3, i * .01 + .6, 1.8, 3.6);
            colored[i] = new ColoredBox(i, 0, -30, i + 1, 2, -29, .2f, .8f, 1, .35f);
        }
        Vec3 eye = new Vec3(0, 1.6, 0), look = new Vec3(0, 0, 1);
        Predicate<Vec3> visible = point -> point.x < .6 && point.y > .1;
        measure(
                "surface-reference",
                2000,
                5000,
                i ->
                        sink =
                                AimSearchPerformanceVerification.reference(
                                        boxes[i & 31], eye, look, 6, .72, true, visible));
        measure(
                "surface-optimized",
                2000,
                5000,
                i ->
                        sink =
                                AimPointsC.findBestSurfacePoint(
                                        boxes[i & 31], eye, look, 6, .72, true, visible));
        var pose = new Matrix4f().translate(-100, -50, -200).rotateXYZ(.3f, 1.2f, -.1f);
        var vertices = new VertexSink();
        measure(
                "overlay-reference",
                20_000,
                200_000,
                i -> {
                    vertices.offset = 0;
                    ReferenceOverlayGeometry.renderSoftFill(pose, vertices, colored[i & 31]);
                    ReferenceOverlayGeometry.renderOutlineBox(pose, vertices, colored[i & 31]);
                    vertexSink = vertices.values[(i & 31) * 3];
                });
        measure(
                "overlay-optimized",
                20_000,
                200_000,
                i -> {
                    vertices.offset = 0;
                    OverlayGeometry.renderSoftFill(pose, vertices, colored[i & 31]);
                    OverlayGeometry.renderOutlineBox(pose, vertices, colored[i & 31]);
                    vertexSink = vertices.values[(i & 31) * 3];
                });
        LearnedAimModel model = LearnedAimModel.bundled();
        if (model == null) throw new AssertionError("Missing model");
        float[][] input = new float[16][7];
        for (float[] row : input) row[6] = 50;
        var workspace = new LearnedAimModel.Workspace();
        measure("inference-fresh", 300, 1000, i -> sink = model.predict(input));
        measure("inference-reused", 300, 1000, i -> sink = model.predict(input, workspace));
    }

    private static void measure(String name, int warmup, int count, IntConsumer operation) {
        for (int i = 0; i < warmup; i++) operation.accept(i);
        double[] times = new double[5];
        long[] allocations = new long[5];
        for (int sample = 0; sample < times.length; sample++) {
            long bytes = allocated();
            long start = System.nanoTime();
            for (int i = 0; i < count; i++) operation.accept(i);
            long elapsed = System.nanoTime() - start;
            allocations[sample] = ALLOCATIONS == null ? -1 : (allocated() - bytes) / count;
            times[sample] = elapsed / (count * 1000.0);
        }
        Arrays.sort(times);
        Arrays.sort(allocations);
        System.out.printf(
                Locale.ROOT,
                "HOT_PATH name=%s median-us/op=%.3f bytes/op=%d samples=5%n",
                name,
                times[2],
                allocations[2]);
    }

    private static com.sun.management.ThreadMXBean allocations() {
        var bean = ManagementFactory.getThreadMXBean();
        if (!(bean instanceof com.sun.management.ThreadMXBean value)
                || !value.isThreadAllocatedMemorySupported()) return null;
        value.setThreadAllocatedMemoryEnabled(true);
        return value;
    }

    private static long allocated() {
        return ALLOCATIONS == null
                ? 0
                : ALLOCATIONS.getThreadAllocatedBytes(Thread.currentThread().threadId());
    }

    private static final class VertexSink implements VertexConsumer {
        private final float[] values = new float[48 * 7];
        private int offset;

        public VertexConsumer addVertex(float x, float y, float z) {
            values[offset++] = x;
            values[offset++] = y;
            values[offset++] = z;
            return this;
        }

        public VertexConsumer setColor(int r, int g, int b, int a) {
            values[offset++] = r;
            values[offset++] = g;
            values[offset++] = b;
            values[offset++] = a;
            return this;
        }

        public VertexConsumer setColor(int color) {
            return setColor(color >> 16 & 255, color >> 8 & 255, color & 255, color >>> 24);
        }

        public VertexConsumer setUv(float u, float v) {
            return this;
        }

        public VertexConsumer setUv1(int u, int v) {
            return this;
        }

        public VertexConsumer setUv2(int u, int v) {
            return this;
        }

        public VertexConsumer setUv3(float u, float v) {
            return this;
        }

        public VertexConsumer setNormal(float x, float y, float z) {
            return this;
        }

        public VertexConsumer setLineWidth(float width) {
            return this;
        }
    }
}
