package com.blanoir.tempos.client.utils.math;

import net.minecraft.world.phys.Vec3;

/** Angular distance and view-cone membership. */
public final class ViewCone {
    private static final double EPSILON = 1.0E-9D;

    private ViewCone() {}

    /** Smallest three-dimensional angle between two directions, in degrees. */
    public static double angleBetween(Vec3 first, Vec3 second) {
        if (first == null
                || second == null
                || first.lengthSqr() < EPSILON
                || second.lengthSqr() < EPSILON) {
            return 0.0D;
        }
        double dot = first.normalize().dot(second.normalize());
        return Math.toDegrees(Math.acos(Math.clamp(dot, -1.0D, 1.0D)));
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
                && viewAngle <= Math.clamp(configuredFov, 0.0D, 360.0D) * 0.5D + EPSILON;
    }
}
