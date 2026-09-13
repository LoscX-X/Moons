package com.blanoir.moons.client.utils.rotation.aim;

import com.blanoir.moons.client.utils.raytrace.RaytraceUtils;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * E: AimAssist candidate-point policy. Null mode selects the original Legit visible-ray
 * search; Center/Closest probe A/B geometry, then C surface fallback. Stateless and consumes
 * no random values, so scanning rejected candidates never advances a selected-target anchor.
 * Returns null when the original visibility search has no usable point.
 */
public final class AimPointsE {
    private AimPointsE() {}

    private static final double AIM_POINT_HYSTERESIS_DEGREES = 2.0D;

    public static Vec3 resolve(
            double range,
            AimGeometry.Mode mode,
            Minecraft client,
            LivingEntity entity,
            Vec3 preferredAimPoint) {
        Vec3 eyePos = client.player.getEyePosition();

        Vec3 aimPoint =
                mode == null
                        ? AimPointsC.findVisibleAimPoint(
                                client,
                                entity,
                                preferredAimPoint,
                                range,
                                AIM_POINT_HYSTERESIS_DEGREES)
                        : mode == AimGeometry.Mode.CENTER
                                ? AimPointsA.centerTrackingPoint(eyePos, entity.getBoundingBox())
                                : AimPointsB.closestTrackingPoint(eyePos, entity.getBoundingBox());
        if (mode != null
                && (eyePos.distanceToSqr(aimPoint) > range * range
                        || !RaytraceUtils.canRayTraceTo(client, eyePos, aimPoint))) {
            aimPoint =
                    AimPointsC.findBestVisibleSurfacePoint(
                            client,
                            entity.getBoundingBox(),
                            client.player.getLookAngle(),
                            range,
                            .72D,
                            false);
        }
        if (aimPoint == null) {
            return null;
        }

        return aimPoint;
    }
}
