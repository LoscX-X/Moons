package com.blanoir.moons.client.module.impl.misc.aimdata;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Pure snapshot geometry. Never reads Minecraft objects on the writer thread. */
public final class AimGeometry {
    private AimGeometry() {}

    public record Point(double x, double y, double z) {
        public Point subtract(Point other) {
            return new Point(x - other.x, y - other.y, z - other.z);
        }

        public double length() {
            return Math.sqrt(x * x + y * y + z * z);
        }
    }

    public record Box(
            double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        Point at(double x, double y, double z) {
            return new Point(
                    minX + (maxX - minX) * x, minY + (maxY - minY) * y, minZ + (maxZ - minZ) * z);
        }
    }

    public record Actor(
            int id,
            String uuid,
            Point position,
            Point eye,
            Box box,
            float bodyYaw,
            float pitch,
            float headYaw,
            boolean rotationKnown,
            boolean headKnown,
            long positionNanos,
            long rotationNanos,
            long headNanos,
            boolean onGround,
            boolean crouching,
            boolean sprinting,
            String pose,
            boolean local,
            int latencyMs) {
        public float aimYaw() {
            return headKnown ? headYaw : bodyYaw;
        }
    }

    public record Sample(
            long sequence,
            long receivedNanos,
            long appliedNanos,
            long receivedEpochMillis,
            long appliedEpochMillis,
            long worldTick,
            long combatId,
            long lastDamageNanos,
            long combatUntilNanos,
            int combatOpponentId,
            long preRollEvicted,
            String packet,
            boolean positionChanged,
            boolean rotationChanged,
            boolean headChanged,
            boolean discontinuity,
            int action,
            int damageTargetId,
            Actor observer,
            List<Actor> candidates,
            boolean candidatesTruncated,
            double targetRange) {
        public Sample {
            candidates = List.copyOf(candidates);
        }

        Sample withCombat(
                long sequence,
                long combatId,
                long damageNanos,
                long until,
                int opponent,
                long evicted) {
            return new Sample(
                    sequence,
                    receivedNanos,
                    appliedNanos,
                    receivedEpochMillis,
                    appliedEpochMillis,
                    worldTick,
                    combatId,
                    damageNanos,
                    until,
                    opponent,
                    evicted,
                    packet,
                    positionChanged,
                    rotationChanged,
                    headChanged,
                    discontinuity,
                    action,
                    damageTargetId,
                    observer,
                    candidates,
                    candidatesTruncated,
                    targetRange);
        }
    }

    public record Landmark(
            String name,
            Point point,
            Point relative,
            double distance,
            double yawError,
            double pitchError,
            double angularError) {}

    public record Candidate(
            Actor actor,
            String evidence,
            Double rayDistance,
            double angularError,
            List<Landmark> points) {}

    public static double wrap(double degrees) {
        double result = (degrees + 180) % 360;
        if (result < 0) result += 360;
        return result - 180;
    }

    private static Point direction(double yaw, double pitch) {
        double y = Math.toRadians(yaw), p = Math.toRadians(pitch), c = Math.cos(p);
        return new Point(-Math.sin(y) * c, -Math.sin(p), Math.cos(y) * c);
    }

    /** Returns the first intersection along a finite ray, including zero when inside the box. */
    public static Double intersect(Point origin, Point direction, Box box, double range) {
        double near = 0, far = range;
        double[] o = {origin.x, origin.y, origin.z}, d = {direction.x, direction.y, direction.z};
        double[] min = {box.minX, box.minY, box.minZ}, max = {box.maxX, box.maxY, box.maxZ};
        for (int i = 0; i < 3; i++) {
            if (Math.abs(d[i]) < 1e-10) {
                if (o[i] < min[i] || o[i] > max[i]) return null;
            } else {
                double a = (min[i] - o[i]) / d[i], b = (max[i] - o[i]) / d[i];
                near = Math.max(near, Math.min(a, b));
                far = Math.min(far, Math.max(a, b));
                if (near > far) return null;
            }
        }
        return near;
    }

    private static Landmark landmark(String name, Point point, Actor observer) {
        Point relative = point.subtract(observer.eye);
        double distance = relative.length();
        double yaw = Math.toDegrees(Math.atan2(-relative.x, relative.z));
        double pitch = -Math.toDegrees(Math.atan2(relative.y, Math.hypot(relative.x, relative.z)));
        Point dir = direction(observer.aimYaw(), observer.pitch);
        double dot =
                distance == 0
                        ? 1
                        : (dir.x * relative.x + dir.y * relative.y + dir.z * relative.z) / distance;
        return new Landmark(
                name,
                point,
                relative,
                distance,
                wrap(yaw - observer.aimYaw()),
                pitch - observer.pitch,
                Math.toDegrees(Math.acos(Math.clamp(dot, -1, 1))));
    }

    public static List<Landmark> landmarks(Actor observer, Actor target) {
        List<Landmark> points = new ArrayList<>();
        Box b = target.box;
        points.add(landmark("eye", target.eye, observer));
        String[] names = {"feet", "knees", "pelvis", "abdomen", "chest", "head", "top"};
        double[] heights = {0, .25, .45, .60, .75, .90, 1};
        for (int i = 0; i < names.length; i++)
            points.add(landmark(names[i], b.at(.5, heights[i], .5), observer));
        for (int x = 0; x <= 1; x++)
            for (int y = 0; y <= 1; y++)
                for (int z = 0; z <= 1; z++)
                    points.add(landmark("corner_" + x + y + z, b.at(x, y, z), observer));
        points.add(
                landmark(
                        "nearest_box",
                        new Point(
                                Math.clamp(observer.eye.x, b.minX, b.maxX),
                                Math.clamp(observer.eye.y, b.minY, b.maxY),
                                Math.clamp(observer.eye.z, b.minZ, b.maxZ)),
                        observer));
        Point dir = direction(observer.aimYaw(), observer.pitch);
        // Distance to a convex box along a finite ray is convex; minimize it without game state.
        double lo = 0, hi = 64;
        for (int i = 0; i < 32; i++) {
            double a = lo + (hi - lo) / 3, c = hi - (hi - lo) / 3;
            if (rayBoxDistance(observer.eye, dir, b, a) < rayBoxDistance(observer.eye, dir, b, c))
                hi = c;
            else lo = a;
        }
        double t = (lo + hi) / 2;
        points.add(
                landmark(
                        "nearest_ray_box",
                        clamp(
                                new Point(
                                        observer.eye.x + dir.x * t,
                                        observer.eye.y + dir.y * t,
                                        observer.eye.z + dir.z * t),
                                b),
                        observer));
        Double hit = intersect(observer.eye, dir, b, 64);
        if (hit != null)
            points.add(
                    landmark(
                            "ray_hit",
                            new Point(
                                    observer.eye.x + dir.x * hit,
                                    observer.eye.y + dir.y * hit,
                                    observer.eye.z + dir.z * hit),
                            observer));
        return List.copyOf(points);
    }

    private static Point clamp(Point point, Box box) {
        return new Point(
                Math.clamp(point.x, box.minX, box.maxX),
                Math.clamp(point.y, box.minY, box.maxY),
                Math.clamp(point.z, box.minZ, box.maxZ));
    }

    private static double rayBoxDistance(Point eye, Point direction, Box box, double t) {
        Point point =
                new Point(
                        eye.x + direction.x * t, eye.y + direction.y * t, eye.z + direction.z * t);
        return point.subtract(clamp(point, box)).length();
    }

    /** Top three geometric hypotheses; walls/intent cannot be verified from entity packets. */
    public static List<Candidate> candidates(Sample sample) {
        Actor observer = sample.observer;
        if (!observer.rotationKnown) return List.of();
        Point direction = direction(observer.aimYaw(), observer.pitch);
        List<Candidate> result = new ArrayList<>();
        for (Actor target : sample.candidates) {
            if (target.id == observer.id) continue;
            List<Landmark> points = landmarks(observer, target);
            double distance = points.stream().mapToDouble(Landmark::distance).min().orElseThrow();
            if (distance > sample.targetRange) continue;
            Double ray = intersect(observer.eye, direction, target.box, sample.targetRange);
            double angle = points.stream().mapToDouble(Landmark::angularError).min().orElseThrow();
            if (ray == null && angle > 30) continue;
            result.add(
                    new Candidate(
                            target,
                            ray == null ? "cone_inferred" : "ray_box_inferred",
                            ray,
                            angle,
                            points));
        }
        result.sort(
                Comparator.comparing((Candidate c) -> c.rayDistance == null)
                        .thenComparingDouble(
                                c -> c.rayDistance == null ? c.angularError : c.rayDistance)
                        .thenComparingInt(c -> c.actor.id));
        return List.copyOf(result.subList(0, Math.min(3, result.size())));
    }
}
