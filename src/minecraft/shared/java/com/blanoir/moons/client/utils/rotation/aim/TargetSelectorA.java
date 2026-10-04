package com.blanoir.moons.client.utils.rotation.aim;

import com.blanoir.moons.client.compat.math.VecMath;
import com.blanoir.moons.client.management.targeting.Targeting;
import com.blanoir.moons.client.utils.combat.CombatGeometry;
import com.blanoir.moons.client.utils.combat.CombatReach;
import com.blanoir.moons.client.utils.math.MathUtils;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.monster.IMob;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * A: SilentAura candidate filtering and lexicographic ranking, shared by Latest and Legacy.
 * Scans locked target first, prioritizes attackable candidates, then preserves the original
 * type/distance/angle/health/hurt-time/lock/id ordering. Evaluation is read-only and samples
 * no randomness. The mode owns cadence, retained-target decisions and attack-triggered switching.
 */
public final class TargetSelectorA {
    private TargetSelectorA() {}

    private static final double UPPER_BODY_ANCHOR = 0.72D;

    public record Parameters(
            boolean targetPlayers,
            java.util.Set<net.minecraft.util.ResourceLocation> targetEntityTypes,
            double fov,
            int hurtTime,
            double aimRange,
            double scanExtra,
            boolean lockMode,
            boolean throughBlocks) {}

    public record Candidate(
            EntityLivingBase entity,
            Vec3 point,
            double distanceSquared,
            boolean attackable,
            boolean locked) {}

    public record Candidates(List<Candidate> sorted, Candidate retained) {}

    public static Candidates evaluate(
            Parameters parameters,
            Minecraft client,
            Vec3 referenceLook,
            EntityLivingBase locked,
            double attackRange,
            double scanRange) {
        List<Candidate> candidates = new ArrayList<>();
        Candidate lockedCandidate =
                candidate(parameters, client, locked, referenceLook, attackRange, scanRange, true);
        if (lockedCandidate != null) candidates.add(lockedCandidate);

        AxisAlignedBB searchBox =
                VecMath.inflate(
                        client.thePlayer.getEntityBoundingBox(),
                        scanRange + CombatGeometry.searchPadding());
        for (EntityLivingBase entity :
                client.theWorld.getEntitiesWithinAABB(
                        EntityLivingBase.class,
                        searchBox,
                        entity ->
                                entity != locked
                                        && acquisitionEligible(
                                                parameters, client, entity, scanRange))) {
            Candidate candidate =
                    candidate(
                            parameters,
                            client,
                            entity,
                            referenceLook,
                            attackRange,
                            scanRange,
                            false);
            if (candidate != null) candidates.add(candidate);
        }

        boolean hasAttackable = candidates.stream().anyMatch(Candidate::attackable);
        if (hasAttackable) candidates.removeIf(candidate -> !candidate.attackable());
        candidates.sort(candidateComparator(parameters, client));

        Candidate retained =
                locked == null
                        ? null
                        : candidates.stream()
                                .filter(candidate -> candidate.entity() == locked)
                                .findFirst()
                                .orElse(null);
        return new Candidates(candidates, retained);
    }

    public static Candidate candidate(
            Parameters parameters,
            Minecraft client,
            EntityLivingBase entity,
            Vec3 referenceLook,
            double attackRange,
            double scanRange,
            boolean locked) {
        if (!trackingEligible(parameters, client, entity, scanRange)) return null;
        double distanceSquared = CombatGeometry.distanceSquared(client, entity);
        Vec3 point =
                distanceSquared <= attackRange * attackRange
                        ? visibleAimPoint(parameters, client, entity, referenceLook, attackRange)
                        : null;
        boolean attackable = point != null;
        if (point == null)
            point = visibleAimPoint(parameters, client, entity, referenceLook, scanRange);
        return point == null
                ? null
                : new Candidate(entity, point, distanceSquared, attackable, locked);
    }

    public static boolean trackingEligible(
            Parameters parameters, Minecraft client, EntityLivingBase entity, double range) {
        if (entity == null) return false;
        if (!Targeting.isConfiguredTarget(
                client, entity, parameters.targetPlayers(), false, parameters.targetEntityTypes()))
            return false;
        if (CombatGeometry.distanceSquared(client, entity) > range * range) return false;
        // FOV is a camera-space ownership boundary, not merely an acquisition
        // hint.  Recheck it for the cached and already locked target as well;
        // otherwise one successful acquisition turns every configured FOV into
        // a persistent 360-degree lock.
        Vec3 center = VecMath.center(CombatGeometry.box(client, entity));
        return MathUtils.withinFov(viewAngle(parameters, client, center), parameters.fov());
    }

    public static boolean acquisitionEligible(
            Parameters parameters, Minecraft client, EntityLivingBase entity, double range) {
        return trackingEligible(parameters, client, entity, range)
                && entity.hurtTime <= parameters.hurtTime();
    }

    public static Comparator<Candidate> candidateComparator(
            Parameters parameters, Minecraft client) {
        return Comparator.comparingInt(
                        (Candidate candidate) -> typeRank(parameters, client, candidate.entity()))
                .thenComparingDouble(Candidate::distanceSquared)
                .thenComparingDouble(candidate -> angle(parameters, client, candidate.entity()))
                .thenComparingDouble(
                        candidate ->
                                candidate.entity().getHealth()
                                        + candidate.entity().getAbsorptionAmount())
                .thenComparingInt(candidate -> candidate.entity().hurtTime)
                .thenComparingInt(candidate -> candidate.locked() ? 0 : 1)
                .thenComparingInt(candidate -> candidate.entity().getEntityId());
    }

    public static double angle(Parameters parameters, Minecraft client, EntityLivingBase entity) {
        return viewAngle(parameters, client, VecMath.center(CombatGeometry.box(client, entity)));
    }

    public static int typeRank(Parameters parameters, Minecraft client, EntityLivingBase entity) {
        if (entity instanceof EntityPlayer) return 0;
        if (entity instanceof IMob) return 1;
        if (entity instanceof EntityLiving neutral
                && neutral.getAttackTarget() == client.thePlayer) {
            return 2;
        }
        return 3;
    }

    public static double viewAngle(Parameters parameters, Minecraft client, Vec3 point) {
        return AimSolverD.viewAngle(
                client.thePlayer.getPositionEyes(1F), client.thePlayer.getLook(1.0F), point);
    }

    public static boolean validClient(Parameters parameters, Minecraft client) {
        return client != null && client.thePlayer != null && client.theWorld != null;
    }

    public static double attackRange(Parameters parameters, Minecraft client) {
        return CombatReach.entityInteractionRange(client, parameters.aimRange());
    }

    public static double scanRange(Parameters parameters, Minecraft client) {
        return CombatReach.scanRange(client, parameters.aimRange(), parameters.scanExtra());
    }

    public static Vec3 visibleAimPoint(
            Parameters parameters,
            Minecraft client,
            EntityLivingBase target,
            Vec3 look,
            double range) {
        if (!validClient(parameters, client) || target == null || range <= 0.0D) return null;
        AxisAlignedBB aimBox = CombatGeometry.box(client, target);
        return AimPointsC.findBestVisibleSurfacePoint(
                client,
                aimBox,
                look,
                range,
                UPPER_BODY_ANCHOR,
                parameters.lockMode(),
                parameters.throughBlocks());
    }
}
