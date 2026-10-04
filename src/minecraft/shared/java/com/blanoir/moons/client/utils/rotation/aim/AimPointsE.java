package com.blanoir.moons.client.utils.rotation.aim;

import com.blanoir.moons.client.utils.raytrace.RaytraceUtils;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.Vec3;

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
            EntityLivingBase entity,
            Vec3 preferredAimPoint) {
        Vec3 eyePos = client.thePlayer.getPositionEyes(1F);

        Vec3 aimPoint =
                mode == null
                        ? AimPointsC.findVisibleAimPoint(
                                client,
                                entity,
                                preferredAimPoint,
                                range,
                                AIM_POINT_HYSTERESIS_DEGREES)
                        : mode == AimGeometry.Mode.CENTER
                                ? AimPointsA.centerTrackingPoint(
                                        eyePos, entity.getEntityBoundingBox())
                                : AimPointsB.closestTrackingPoint(
                                        eyePos, entity.getEntityBoundingBox());
        if (mode != null
                && (eyePos.squareDistanceTo(aimPoint) > range * range
                        || !RaytraceUtils.canRayTraceTo(client, eyePos, aimPoint))) {
            aimPoint =
                    AimPointsC.findBestVisibleSurfacePoint(
                            client,
                            entity.getEntityBoundingBox(),
                            client.thePlayer.getLook(1F),
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
