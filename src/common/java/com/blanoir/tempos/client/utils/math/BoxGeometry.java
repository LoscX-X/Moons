package com.blanoir.tempos.client.utils.math;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Closest points, distances and insets for axis-aligned boxes. */
public final class BoxGeometry {
    private BoxGeometry() {}

    public static double distanceToAabb(Vec3 point, AABB box) {
        double dx = Math.max(Math.max(box.minX - point.x, 0.0D), point.x - box.maxX);
        double dy = Math.max(Math.max(box.minY - point.y, 0.0D), point.y - box.maxY);
        double dz = Math.max(Math.max(box.minZ - point.z, 0.0D), point.z - box.maxZ);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    public static Vec3 closestPoint(Vec3 point, AABB box) {
        if (point == null || box == null) {
            return Vec3.ZERO;
        }
        return new Vec3(
                Math.clamp(point.x, box.minX, box.maxX),
                Math.clamp(point.y, box.minY, box.maxY),
                Math.clamp(point.z, box.minZ, box.maxZ));
    }

    public static double squaredDistanceToBox(Vec3 point, AABB box) {
        if (point == null || box == null) {
            return Double.MAX_VALUE;
        }
        return point.distanceToSqr(
                Math.clamp(point.x, box.minX, box.maxX),
                Math.clamp(point.y, box.minY, box.maxY),
                Math.clamp(point.z, box.minZ, box.maxZ));
    }

    public static AABB inset(AABB box, double requestedInset, double maxSizeFraction) {
        if (box == null) {
            return null;
        }
        double amount = Math.max(0.0D, requestedInset);
        double fraction = Math.clamp(maxSizeFraction, 0.0D, 0.499D);
        double x = Math.min(amount, box.getXsize() * fraction);
        double y = Math.min(amount, box.getYsize() * fraction);
        double z = Math.min(amount, box.getZsize() * fraction);
        return new AABB(
                box.minX + x, box.minY + y, box.minZ + z, box.maxX - x, box.maxY - y, box.maxZ - z);
    }
}
