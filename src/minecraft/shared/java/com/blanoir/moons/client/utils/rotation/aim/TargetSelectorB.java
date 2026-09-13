package com.blanoir.moons.client.utils.rotation.aim;

import com.blanoir.moons.client.management.targeting.Targeting;
import com.blanoir.moons.client.utils.math.MathUtils;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.List;

/**
 * B: AimAssist weighted angle/distance/health/threat ranking with 3-degree target hysteresis.
 * Re-evaluates the held entity after the candidate scan in the original order. The caller
 * owns lockedEntityId/lockedAimPoint and frame cadence; this evaluator does not mutate them
 * or consume randomness. Returns null when no candidate survives range/FOV/visibility.
 */
public final class TargetSelectorB {
    private TargetSelectorB() {}

    private static final double TARGET_SWITCH_HYSTERESIS_DEGREES = 3.0D;

    public static AimSolverA.Result select(
            Minecraft client,
            double range,
            double fov,
            AimGeometry.Mode mode,
            int lockedEntityId,
            Vec3 lockedAimPoint) {
        AABB searchBox = client.player.getBoundingBox().inflate(range);

        List<LivingEntity> candidates =
                client.level.getEntitiesOfClass(
                        LivingEntity.class,
                        searchBox,
                        entity -> Targeting.isEnemyPlayer(client, entity));

        AimSolverA.Result best =
                candidates.stream()
                        .map(
                                entity ->
                                        targetRotation(
                                                range,
                                                mode,
                                                client,
                                                entity,
                                                lockedEntityId == entity.getId()
                                                        ? lockedAimPoint
                                                        : null))
                        .filter(
                                target ->
                                        target != null && target.distanceSquared() <= range * range)
                        .filter(target -> MathUtils.withinFov(target.angle(), fov))
                        .min(
                                Comparator.comparingDouble(
                                        target -> targetSelectionScore(client, target)))
                        .orElse(null);

        if (best == null || lockedEntityId == -1) {
            return best;
        }

        Entity lockedEntity = client.level.getEntity(lockedEntityId);

        if (lockedEntity instanceof LivingEntity livingLocked
                && Targeting.isEnemyPlayer(client, livingLocked)) {

            AimSolverA.Result lockedRotation =
                    targetRotation(range, mode, client, livingLocked, lockedAimPoint);

            if (lockedRotation != null
                    && lockedRotation.distanceSquared() <= range * range
                    && MathUtils.withinFov(lockedRotation.angle(), fov)
                    && lockedRotation.angle() <= best.angle() + TARGET_SWITCH_HYSTERESIS_DEGREES) {
                return lockedRotation;
            }
        }

        return best;
    }

    private static double targetSelectionScore(Minecraft client, AimSolverA.Result target) {
        LivingEntity entity = target.entity();

        double distance = Math.sqrt(target.distanceSquared());

        double healthRatio =
                (entity.getHealth() + entity.getAbsorptionAmount())
                        / Math.max(1.0D, entity.getMaxHealth());

        double hurtFramePenalty = entity.hurtTime > 0 ? 0.8D : 0.0D;

        Vec3 towardPlayer = client.player.getEyePosition().subtract(entity.getEyePosition());

        double threatBonus =
                towardPlayer.lengthSqr() > 1.0E-6D
                                && entity.getLookAngle().dot(towardPlayer.normalize()) > 0.72D
                        ? -0.65D
                        : 0.0D;

        return target.angle()
                + distance * 0.32D
                + Mth.clamp(healthRatio, 0.0D, 1.5D) * 1.15D
                + hurtFramePenalty
                + threatBonus;
    }

    private static AimSolverA.Result targetRotation(
            double range,
            AimGeometry.Mode mode,
            Minecraft client,
            LivingEntity entity,
            Vec3 preferredAimPoint) {
        Vec3 point = AimPointsE.resolve(range, mode, client, entity, preferredAimPoint);
        return point == null ? null : AimSolverA.solve(client, entity, point);
    }
}
