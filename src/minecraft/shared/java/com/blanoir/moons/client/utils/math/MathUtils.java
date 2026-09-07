package com.blanoir.moons.client.utils.math;

import com.blanoir.moons.client.utils.rotation.Rotation;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Pure geometry, distance, and angle calculations shared by client features. */
public final class MathUtils {
    private static final double EPSILON = 1.0E-9D;

    private MathUtils() {}

    public static Vec3 closestPoint(Vec3 point, AABB box) {
        if (point == null || box == null) {
            return Vec3.ZERO;
        }
        return new Vec3(
                Mth.clamp(point.x, box.minX, box.maxX),
                Mth.clamp(point.y, box.minY, box.maxY),
                Mth.clamp(point.z, box.minZ, box.maxZ));
    }

    public static double squaredDistanceToBox(Vec3 point, AABB box) {
        if (point == null || box == null) {
            return Double.MAX_VALUE;
        }
        return point.distanceToSqr(closestPoint(point, box));
    }

    public static AABB inset(AABB box, double requestedInset, double maxSizeFraction) {
        if (box == null) {
            return null;
        }
        double amount = Math.max(0.0D, requestedInset);
        double fraction = Mth.clamp(maxSizeFraction, 0.0D, 0.499D);
        double x = Math.min(amount, box.getXsize() * fraction);
        double y = Math.min(amount, box.getYsize() * fraction);
        double z = Math.min(amount, box.getZsize() * fraction);
        return new AABB(
                box.minX + x, box.minY + y, box.minZ + z, box.maxX - x, box.maxY - y, box.maxZ - z);
    }

    public static Rotation rotationTo(Vec3 from, Vec3 to) {
        if (from == null || to == null) {
            return new Rotation(0.0F, 0.0F);
        }
        double deltaX = to.x - from.x;
        double deltaY = to.y - from.y;
        double deltaZ = to.z - from.z;
        double horizontal = Math.hypot(deltaX, deltaZ);
        float yaw = (float) Math.toDegrees(Math.atan2(deltaZ, deltaX)) - 90.0F;
        float pitch = (float) -Math.toDegrees(Math.atan2(deltaY, horizontal));
        return new Rotation(Mth.wrapDegrees(yaw), Mth.clamp(pitch, -90.0F, 90.0F));
    }

    public static double angularDistance(float yaw, float pitch, Rotation target) {
        if (target == null) {
            return Double.MAX_VALUE;
        }
        float yawDifference = Math.abs(Mth.wrapDegrees(target.yaw() - yaw));
        float pitchDifference = Math.abs(target.pitch() - pitch);
        return Math.hypot(yawDifference, pitchDifference);
    }

    public static float wrappedAngleDifference(float current, float target) {
        return Mth.wrapDegrees(target - current);
    }

    /** Moves a scalar by at most the caller's step; this is not exponential smoothing. */
    public static float approach(float current, float target, float maxChange) {
        return current + Mth.clamp(target - current, -maxChange, maxChange);
    }

    public static double approach(double current, double target, double maxChange) {
        return current + Mth.clamp(target - current, -maxChange, maxChange);
    }

    /** Limits the shortest yaw turn while retaining the current whole-turn domain. */
    public static float approachWrapped(float current, float target, float maxChange) {
        return current + Mth.clamp(wrappedAngleDifference(current, target), -maxChange, maxChange);
    }

    /** Cubic interpolation polynomial; callers retain their own input clamping. */
    public static double cubicSmoothStep(double progress) {
        return progress * progress * (3.0D - 2.0D * progress);
    }

    /** Smallest three-dimensional angle between two directions, in degrees. */
    public static double angleBetween(Vec3 first, Vec3 second) {
        if (first == null
                || second == null
                || first.lengthSqr() < EPSILON
                || second.lengthSqr() < EPSILON) {
            return 0.0D;
        }
        double dot = first.normalize().dot(second.normalize());
        return Math.toDegrees(Math.acos(Mth.clamp(dot, -1.0D, 1.0D)));
    }

    /** Three-dimensional visual angle from a view direction to a world point. */
    public static double viewAngle(Vec3 eye, Vec3 look, Vec3 point) {
        if (eye == null || point == null) {
            return Double.MAX_VALUE;
        }
        return angleBetween(look, point.subtract(eye));
    }

    /**
     * Targeting FOV is the complete cone width: 180 means +/-90 degrees in
     * front of the camera, while 360 accepts the full sphere.
     */
    public static boolean withinFov(double viewAngle, double configuredFov) {
        return Double.isFinite(viewAngle)
                && viewAngle <= Mth.clamp(configuredFov, 0.0D, 360.0D) * 0.5D + EPSILON;
    }

    public static float yawTo(Vec3 from, Vec3 to) {
        return rotationTo(from, to).yaw();
    }

    /** Squared distance from a point to a finite view ray segment. */
    public static double squaredDistanceToRay(
            Vec3 rayStart, Vec3 rayDirection, Vec3 point, double rayLength) {
        if (rayStart == null || rayDirection == null || point == null) {
            return Double.MAX_VALUE;
        }
        Vec3 direction =
                rayDirection.lengthSqr() > EPSILON
                        ? rayDirection.normalize()
                        : new Vec3(0.0D, 0.0D, 1.0D);
        double along =
                Mth.clamp(point.subtract(rayStart).dot(direction), 0.0D, Math.max(0.0D, rayLength));
        return point.distanceToSqr(rayStart.add(direction.scale(along)));
    }

    public static float smoothWrappedAngle(float current, float target, double response) {
        return current + wrappedAngleDifference(current, target) * (float) response;
    }
}
