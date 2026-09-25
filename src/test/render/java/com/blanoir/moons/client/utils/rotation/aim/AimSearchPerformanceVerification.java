package com.blanoir.moons.client.utils.rotation.aim;

import com.blanoir.moons.client.utils.entity.EntityDistance;
import com.blanoir.moons.client.utils.math.MathUtils;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.function.Predicate;

/** Differential checks against the pre-optimization sample enumeration and distance expressions. */
public final class AimSearchPerformanceVerification {
    private static final double[] COARSE = {.1, .3, .5, .7, .9};
    private static final double[] PRECISE = {
        .015, .05, .15, .25, .35, .45, .55, .65, .75, .85, .95, .985
    };

    public static void main(String[] args) {
        Random random = new Random(20260925);
        for (int i = 0; i < 600; i++) {
            Vec3 origin = vector(random, i % 4 == 0 ? 30_000_000 : 8);
            AABB box =
                    new AABB(
                            origin,
                            origin.add(
                                    random.nextDouble() * 2,
                                    i % 13 == 0 ? 0 : random.nextDouble() * 3,
                                    random.nextDouble() * 2));
            Vec3 eye = origin.add(vector(random, 5));
            Vec3 look = vector(random, 1).normalize();
            double range = i % 7 == 0 ? 0 : random.nextDouble() * 9;
            double height = random.nextDouble() * 2 - .5;
            boolean precise = (i & 1) == 0;
            int mask = i % 5;
            Predicate<Vec3> visible =
                    p ->
                            mask != 0
                                    && (mask == 1
                                            || Math.floorMod(
                                                            Double.doubleToLongBits(p.x)
                                                                    ^ Double.doubleToLongBits(p.y)
                                                                    ^ Double.doubleToLongBits(p.z),
                                                            5)
                                                    < mask);
            var oldTrace = new ArrayList<Vec3>();
            var newTrace = new ArrayList<Vec3>();
            Vec3 expected =
                    reference(
                            box,
                            eye,
                            look,
                            range,
                            height,
                            precise,
                            p -> {
                                oldTrace.add(p);
                                return visible.test(p);
                            });
            Vec3 actual =
                    AimPointsC.findBestSurfacePoint(
                            box,
                            eye,
                            look,
                            range,
                            height,
                            precise,
                            p -> {
                                newTrace.add(p);
                                return visible.test(p);
                            });
            require(Objects.equals(expected, actual), "Selected point changed: " + i);
            require(oldTrace.equals(newTrace), "Ray order/count changed: " + i);
            require(
                    AimPointsC.aimPoints(box).equals(referenceAimPoints(box)),
                    "Coarse interior sample order changed");
            same(
                    eye.distanceToSqr(EntityDistance.closestPoint(eye, box)),
                    MathUtils.squaredDistanceToBox(eye, box),
                    "Box distance");
        }
        distances(random);
        System.out.println(
                "AIM_SEARCH_PARITY samples=600 ray-order=exact selected-point=exact distances=20000 ordering=stable");
    }

    private static void distances(Random random) {
        var targets = new ArrayList<BlockTarget>();
        Direction[] faces = Direction.values();
        for (int i = 0; i < 20_000; i++) {
            int x = i % 11 == 0 ? Integer.MAX_VALUE : random.nextInt();
            int y = i % 13 == 0 ? Integer.MIN_VALUE : random.nextInt();
            var target = new BlockTarget(new BlockPos(x, y, random.nextInt()), faces[i % 6]);
            Vec3 center = vector(random, 30_000_000);
            same(
                    Vec3.atCenterOf(target.support()).distanceToSqr(center),
                    target.supportDistanceSquared(center),
                    "Support distance");
            same(
                    Vec3.atCenterOf(target.placePos()).distanceToSqr(center),
                    target.placeDistanceSquared(center),
                    "Placement distance, including int wrap");
            if (i < 1000)
                targets.add(new BlockTarget(new BlockPos(i % 7, i % 5, i % 9), faces[i % 6]));
        }
        Vec3 center = new Vec3(.5, .5, .5);
        var reference = new ArrayList<>(targets);
        reference.sort(
                Comparator.comparingDouble(
                                (BlockTarget t) ->
                                        Vec3.atCenterOf(t.placePos()).distanceToSqr(center))
                        .thenComparingDouble(
                                t -> Vec3.atCenterOf(t.support()).distanceToSqr(center))
                        .thenComparingInt(t -> t.face() == Direction.UP ? 0 : 1));
        targets.sort(
                Comparator.comparingDouble((BlockTarget t) -> t.placeDistanceSquared(center))
                        .thenComparingDouble(t -> t.supportDistanceSquared(center))
                        .thenComparingInt(t -> t.face() == Direction.UP ? 0 : 1));
        require(reference.equals(targets), "Stable placement ordering changed");
    }

    // Frozen list-based traversal used before the optimization, including duplicate probes.
    static Vec3 reference(
            AABB box,
            Vec3 eye,
            Vec3 look,
            double range,
            double height,
            boolean precise,
            Predicate<Vec3> visible) {
        List<Vec3> points = new ArrayList<>(156);
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

    private static List<Vec3> referenceAimPoints(AABB box) {
        var points = new ArrayList<Vec3>();
        Vec3 c = box.getCenter();
        points.add(c);
        points.add(new Vec3(c.x, Mth.lerp(.75, box.minY, box.maxY), c.z));
        points.add(new Vec3(c.x, Mth.lerp(.35, box.minY, box.maxY), c.z));
        double ix = Math.min((box.maxX - box.minX) * .15, .1);
        double iy = Math.min((box.maxY - box.minY) * .15, .1);
        double iz = Math.min((box.maxZ - box.minZ) * .15, .1);
        for (double x : new double[] {box.minX + ix, c.x, box.maxX - ix})
            for (double y : new double[] {box.minY + iy, c.y, box.maxY - iy})
                for (double z : new double[] {box.minZ + iz, c.z, box.maxZ - iz})
                    points.add(new Vec3(x, y, z));
        return points;
    }

    private static Vec3 vector(Random random, double scale) {
        return new Vec3(
                (random.nextDouble() - .5) * scale,
                (random.nextDouble() - .5) * scale,
                (random.nextDouble() - .5) * scale);
    }

    private static void same(double expected, double actual, String message) {
        require(Double.doubleToLongBits(expected) == Double.doubleToLongBits(actual), message);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
