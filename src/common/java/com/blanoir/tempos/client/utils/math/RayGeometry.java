package com.blanoir.tempos.client.utils.math;

import net.minecraft.world.phys.Vec3;

/** Distances to finite ray segments. */
public final class RayGeometry {
    private static final double EPSILON = 1.0E-9D;

    private RayGeometry() {}

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
                Math.clamp(
                        point.subtract(rayStart).dot(direction), 0.0D, Math.max(0.0D, rayLength));
        return point.distanceToSqr(rayStart.add(direction.scale(along)));
    }
}
