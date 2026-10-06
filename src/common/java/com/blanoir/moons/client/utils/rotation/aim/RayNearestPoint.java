package com.blanoir.moons.client.utils.rotation.aim;

import com.blanoir.moons.client.compat.math.VecMath;
import com.blanoir.moons.client.utils.entity.EntityDistance;

import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.Vec3;

/**
 * F: Point on a box nearest to a bounded view ray. This is an actual point-selection
 * algorithm, distinct from B's nearest-to-eye anchor. Preserves the 24-step ternary search
 * and fallback look direction. Used by visible entity aiming, prediction and AutoLava.
 */
public final class RayNearestPoint {
    private RayNearestPoint() {}

    public static Vec3 closest(AxisAlignedBB box, Vec3 eye, Vec3 look, double maxDistance) {
        Vec3 direction = normalizedLook(look);
        double low = 0.0D;
        double high = Math.max(0.0D, maxDistance);
        for (int iteration = 0; iteration < 24; iteration++) {
            double first = (low * 2.0D + high) / 3.0D;
            double second = (low + high * 2.0D) / 3.0D;
            double firstDistance =
                    EntityDistance.squaredToBox(eye.add(VecMath.scale(direction, first)), box);
            double secondDistance =
                    EntityDistance.squaredToBox(eye.add(VecMath.scale(direction, second)), box);
            if (firstDistance <= secondDistance) {
                high = second;
            } else {
                low = first;
            }
        }
        Vec3 rayPoint = eye.add(VecMath.scale(direction, (low + high) * 0.5D));
        return EntityDistance.closestPoint(rayPoint, box);
    }

    private static Vec3 normalizedLook(Vec3 look) {
        return look != null && VecMath.lengthSqr(look) > 1.0E-9D
                ? look.normalize()
                : new Vec3(0.0D, 0.0D, 1.0D);
    }
}
