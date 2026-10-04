package com.blanoir.moons.client.utils.rotation.aim;

import com.blanoir.moons.client.compat.math.Mth;
import com.blanoir.moons.client.compat.math.VecMath;
import com.blanoir.moons.client.management.targeting.Targeting;
import com.blanoir.moons.client.utils.math.MathUtils;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.Vec3;

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
        AxisAlignedBB searchBox = VecMath.inflate(client.thePlayer.getEntityBoundingBox(), range);

        List<EntityLivingBase> candidates =
                client.theWorld.getEntitiesWithinAABB(
                        EntityLivingBase.class,
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
                                                lockedEntityId == entity.getEntityId()
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

        Entity lockedEntity = client.theWorld.getEntityByID(lockedEntityId);

        if (lockedEntity instanceof EntityLivingBase livingLocked
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
        EntityLivingBase entity = target.entity();

        double distance = Math.sqrt(target.distanceSquared());

        double healthRatio =
                (entity.getHealth() + entity.getAbsorptionAmount())
                        / Math.max(1.0D, entity.getMaxHealth());

        double hurtFramePenalty = entity.hurtTime > 0 ? 0.8D : 0.0D;

        Vec3 towardPlayer =
                client.thePlayer.getPositionEyes(1F).subtract(entity.getPositionEyes(1F));

        double threatBonus =
                VecMath.lengthSqr(towardPlayer) > 1.0E-6D
                                && entity.getLook(1F).dotProduct(towardPlayer.normalize()) > 0.72D
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
            EntityLivingBase entity,
            Vec3 preferredAimPoint) {
        Vec3 point = AimPointsE.resolve(range, mode, client, entity, preferredAimPoint);
        return point == null ? null : AimSolverA.solve(client, entity, point);
    }
}
