package com.blanoir.tempos.client.module.impl.combat.aim;

import com.blanoir.tempos.client.utils.raytrace.LineOfSight;
import com.blanoir.tempos.client.utils.rotation.aim.AimGeometry;
import com.blanoir.tempos.client.utils.rotation.aim.CenterAimPoints;
import com.blanoir.tempos.client.utils.rotation.aim.ClosestAimPoints;
import com.blanoir.tempos.client.utils.rotation.aim.VisibleAimPoints;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * AimAssist candidate-point policy. Null mode selects the original Legit visible-ray
 * search; Center/Closest probe A/B geometry, then C surface fallback. Stateless and consumes
 * no random values, so scanning rejected candidates never advances a selected-target anchor.
 * Returns null when the original visibility search has no usable point.
 */
public final class AssistCandidatePoints {
    private AssistCandidatePoints() {}

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
                        ? VisibleAimPoints.findVisibleAimPoint(
                                client,
                                entity,
                                preferredAimPoint,
                                range,
                                AIM_POINT_HYSTERESIS_DEGREES)
                        : mode == AimGeometry.Mode.CENTER
                                ? CenterAimPoints.centerTrackingPoint(
                                        eyePos, entity.getBoundingBox())
                                : ClosestAimPoints.closestTrackingPoint(
                                        eyePos, entity.getBoundingBox());
        if (mode != null
                && (eyePos.distanceToSqr(aimPoint) > range * range
                        || !LineOfSight.canRayTraceTo(client, eyePos, aimPoint))) {
            aimPoint =
                    VisibleAimPoints.findBestVisibleSurfacePoint(
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
