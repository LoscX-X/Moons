package com.blanoir.moons.client.utils.rotation.aim;

import com.blanoir.moons.client.module.impl.combat.silentaura.LearnedAimModel;
import com.blanoir.moons.client.render.WorldOverlayRenderer.ColoredBox;
import com.blanoir.moons.client.render.world.OverlayGeometry;
import com.blanoir.moons.client.render.world.ReferenceOverlayGeometry;
import com.blanoir.moons.client.utils.entity.EntityDistance;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import org.joml.Matrix4f;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;
import java.util.function.IntConsumer;
import java.util.function.Predicate;

/** CPU/allocation comparisons only; no GPU, world raycasting or FPS claims. */
public final class HotPathBenchmark {
    private static final double[] COARSE = {.1, .3, .5, .7, .9};
    private static final double[] PRECISE = {
        .015, .05, .15, .25, .35, .45, .55, .65, .75, .85, .95, .985
    };
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
                i -> sink = referenceSurfacePoint(boxes[i & 31], eye, look, 6, .72, true, visible));
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

    /** Frozen list-based traversal for the benchmark's allocation and CPU comparison. */
    private static Vec3 referenceSurfacePoint(
            AABB box,
            Vec3 eye,
            Vec3 look,
            double range,
            double height,
            boolean precise,
            Predicate<Vec3> visible) {
        var points = new ArrayList<Vec3>(156);
        box.clip(eye, eye.add(look.scale(range))).ifPresent(points::add);
        Vec3 closest = EntityDistance.closestPoint(eye, box);
        Vec3 difference = closest.subtract(eye);
        if (difference.lengthSqr() >= 1.0E-9D)
            box.clip(eye, eye.add(difference.scale(2.0D))).ifPresent(points::add);
        points.add(closest);
        for (double a : precise ? PRECISE : COARSE)
            for (double b : precise ? PRECISE : COARSE) {
                double x = Mth.lerp(a, box.minX, box.maxX);
                double y = Mth.lerp(a, box.minY, box.maxY);
                double z = Mth.lerp(b, box.minZ, box.maxZ);
                double xb = Mth.lerp(b, box.minX, box.maxX);
                double yb = Mth.lerp(b, box.minY, box.maxY);
                points.add(new Vec3(box.minX, y, z));
                points.add(new Vec3(box.maxX, y, z));
                points.add(new Vec3(x, box.minY, z));
                points.add(new Vec3(x, box.maxY, z));
                points.add(new Vec3(xb, yb, box.minZ));
                points.add(new Vec3(xb, yb, box.maxZ));
            }
        Vec3 best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        double preferredY = Mth.lerp(Mth.clamp(height, 0.0D, 1.0D), box.minY, box.maxY);
        for (Vec3 point : points) {
            if (eye.distanceToSqr(point) > range * range || !visible.test(point)) continue;
            Vec3 direction = point.subtract(eye).normalize();
            double angularCost = 1.0D - Mth.clamp(look.dot(direction), -1.0D, 1.0D);
            double lowAimPenalty =
                    Math.max(0.0D, preferredY - point.y) / Math.max(box.getYsize(), .1D);
            double score =
                    angularCost * 32.0D + eye.distanceToSqr(point) * .002D + lowAimPenalty * 1.35D;
            if (score < bestScore) {
                bestScore = score;
                best = point;
            }
        }
        return best;
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
