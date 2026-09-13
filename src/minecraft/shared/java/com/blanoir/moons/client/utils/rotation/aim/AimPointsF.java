package com.blanoir.moons.client.utils.rotation.aim;

import com.blanoir.moons.client.utils.entity.EntityDistance;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * F: Point on a box nearest to a bounded view ray. This is an actual point-selection
 * algorithm, distinct from B's nearest-to-eye anchor. Preserves the 24-step ternary search
 * and fallback look direction. Used by visible entity aiming, prediction and AutoLava.
 */
public final class AimPointsF {
    private AimPointsF() {}

    public static Vec3 closest(AABB box, Vec3 eye, Vec3 look, double maxDistance) {
        Vec3 direction = normalizedLook(look);
        double low = 0.0D;
        double high = Math.max(0.0D, maxDistance);
        for (int iteration = 0; iteration < 24; iteration++) {
            double first = (low * 2.0D + high) / 3.0D;
            double second = (low + high * 2.0D) / 3.0D;
            double firstDistance =
                    EntityDistance.squaredToBox(eye.add(direction.scale(first)), box);
            double secondDistance =
                    EntityDistance.squaredToBox(eye.add(direction.scale(second)), box);
            if (firstDistance <= secondDistance) {
                high = second;
            } else {
                low = first;
            }
        }
        Vec3 rayPoint = eye.add(direction.scale((low + high) * 0.5D));
        return EntityDistance.closestPoint(rayPoint, box);
    }

    private static Vec3 normalizedLook(Vec3 look) {
        return look != null && look.lengthSqr() > 1.0E-9D
                ? look.normalize()
                : new Vec3(0.0D, 0.0D, 1.0D);
    }
}
