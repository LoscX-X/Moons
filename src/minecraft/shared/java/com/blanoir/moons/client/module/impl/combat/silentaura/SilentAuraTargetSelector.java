package com.blanoir.moons.client.module.impl.combat.silentaura;

import com.blanoir.moons.client.management.targeting.Targeting;
import com.blanoir.moons.client.utils.combat.CombatReach;
import com.blanoir.moons.client.utils.entity.EntityDistance;
import com.blanoir.moons.client.utils.math.MathUtils;
import com.blanoir.moons.client.utils.raytrace.RaytraceUtils;
import com.blanoir.moons.client.utils.rotation.aim.AimPointManager;
import com.blanoir.moons.client.utils.rotation.aim.AimPointUtils;
import com.blanoir.moons.client.utils.rotation.aim.RotationUtils;
import com.blanoir.moons.client.utils.rotation.aim.VisibleAimPoints;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Target tracking and box-surface aim-point resolution. */
public final class SilentAuraTargetSelector {
    private static final double UPPER_BODY_ANCHOR = 0.72D;

    private final boolean lockMode;
    private final boolean fullLockMode;

    private int targetId = -1;
    private int selectionTick = Integer.MIN_VALUE;
    private final AimPointManager aimPoints = new AimPointManager();
    private boolean reevaluateAfterAttack;
    private int reevaluateTick = Integer.MIN_VALUE;

    public SilentAuraTargetSelector(boolean lockMode) {
        this(lockMode, false);
    }

    SilentAuraTargetSelector(boolean lockMode, boolean fullLockMode) {
        this.lockMode = lockMode;
        this.fullLockMode = fullLockMode;
    }

    public LivingEntity select(Minecraft client, Vec3 referenceLook) {
        if (!validClient(client)) {
            clear();
            return null;
        }

        double attackRange = attackRange(client);
        double scanRange = scanRange(client);
        if (attackRange <= 0.0D || scanRange <= 0.0D) {
            clear();
            return null;
        }

        LivingEntity locked = current(client);
        if (selectionTick == client.player.tickCount
                && trackingEligible(client, locked, scanRange)) {
            return locked;
        }
        selectionTick = client.player.tickCount;

        List<Candidate> candidates = new ArrayList<>();
        Candidate lockedCandidate =
                candidate(client, locked, referenceLook, attackRange, scanRange, true);
        if (lockedCandidate != null) candidates.add(lockedCandidate);

        AABB searchBox = client.player.getBoundingBox().inflate(scanRange + 1.0D);
        for (LivingEntity entity :
                client.level.getEntitiesOfClass(
                        LivingEntity.class,
                        searchBox,
                        entity ->
                                entity != locked
                                        && acquisitionEligible(client, entity, scanRange))) {
            Candidate candidate =
                    candidate(client, entity, referenceLook, attackRange, scanRange, false);
            if (candidate != null) candidates.add(candidate);
        }

        boolean hasAttackable = candidates.stream().anyMatch(Candidate::attackable);
        if (hasAttackable) candidates.removeIf(candidate -> !candidate.attackable());
        candidates.sort(candidateComparator(client));

        Candidate retained =
                locked == null
                        ? null
                        : candidates.stream()
                                .filter(candidate -> candidate.entity() == locked)
                                .findFirst()
                                .orElse(null);
        boolean reevaluateNow =
                SilentAuraConfig.switchTargetMode()
                        && reevaluateAfterAttack
                        && client.player.tickCount >= reevaluateTick;
        boolean keepCurrent = retained != null && !reevaluateNow;
        Candidate selected =
                keepCurrent ? retained : candidates.isEmpty() ? null : candidates.getFirst();
        if (reevaluateNow || retained == null) reevaluateAfterAttack = false;
        if (selected == null) {
            clear();
            return null;
        }

        targetId = selected.entity().getId();
        return selected.entity();
    }

    public LivingEntity current(Minecraft client) {
        if (!validClient(client) || targetId < 0) return null;
        Entity entity = client.level.getEntity(targetId);
        return entity instanceof LivingEntity living ? living : null;
    }

    public boolean inAttackRange(Minecraft client, LivingEntity target) {
        if (!validClient(client) || target == null) return false;
        double range = attackRange(client);
        return range > 0.0D && EntityDistance.squaredToEntity(client, target) <= range * range;
    }

    public Vec3 aimPoint(Minecraft client, LivingEntity target, Vec3 look) {
        if (!validClient(client) || target == null) return null;
        // Never reuse a world-space aim point. Resolve it from the target's
        // current bounding box on every frame, including in balance mode.
        if (target.getId() == targetId && trackingEligible(client, target, scanRange(client))) {
            return trackingAimPoint(client, target, look);
        }
        double attackRange = attackRange(client);
        double range =
                EntityDistance.squaredToEntity(client, target) <= attackRange * attackRange
                        ? attackRange
                        : scanRange(client);
        return visibleAimPoint(client, target, look, range);
    }

    public void clear() {
        targetId = -1;
        selectionTick = Integer.MIN_VALUE;
        aimPoints.reset();
        reevaluateAfterAttack = false;
        reevaluateTick = Integer.MIN_VALUE;
    }

    /** High-version switch mode re-ranks only after a real cooldown-gated hit. */
    public void onAttack(Minecraft client, LivingEntity attacked) {
        var currentPlayer = client == null ? null : client.player;
        if (!SilentAuraConfig.switchTargetMode()
                || attacked == null
                || attacked.getId() != targetId) return;
        reevaluateAfterAttack = true;
        reevaluateTick =
                client != null && currentPlayer != null
                        ? currentPlayer.tickCount + 1
                        : Integer.MIN_VALUE;
        selectionTick = Integer.MIN_VALUE;
    }

    private Candidate candidate(
            Minecraft client,
            LivingEntity entity,
            Vec3 referenceLook,
            double attackRange,
            double scanRange,
            boolean locked) {
        if (!trackingEligible(client, entity, scanRange)) return null;
        double distanceSquared = EntityDistance.squaredToEntity(client, entity);
        Vec3 point =
                distanceSquared <= attackRange * attackRange
                        ? visibleAimPoint(client, entity, referenceLook, attackRange)
                        : null;
        boolean attackable = point != null;
        if (point == null) point = visibleAimPoint(client, entity, referenceLook, scanRange);
        return point == null
                ? null
                : new Candidate(entity, point, distanceSquared, attackable, locked);
    }

    private boolean trackingEligible(Minecraft client, LivingEntity entity, double range) {
        if (entity == null) return false;
        if (!Targeting.isConfiguredTarget(
                client,
                entity,
                SilentAuraConfig.targetPlayers(),
                false,
                SilentAuraConfig.targetEntityTypes())) return false;
        if (EntityDistance.squaredToEntity(client, entity) > range * range) return false;
        // FOV is a camera-space ownership boundary, not merely an acquisition
        // hint.  Recheck it for the cached and already locked target as well;
        // otherwise one successful acquisition turns every configured FOV into
        // a persistent 360-degree lock.
        Vec3 center = entity.getBoundingBox().getCenter();
        return MathUtils.withinFov(viewAngle(client, center), SilentAuraConfig.fov());
    }

    private boolean acquisitionEligible(Minecraft client, LivingEntity entity, double range) {
        return trackingEligible(client, entity, range)
                && entity.hurtTime <= SilentAuraConfig.hurtTime();
    }

    private Comparator<Candidate> candidateComparator(Minecraft client) {
        return Comparator.comparingInt(
                        (Candidate candidate) -> typeRank(client, candidate.entity()))
                .thenComparingDouble(Candidate::distanceSquared)
                .thenComparingDouble(candidate -> angle(client, candidate.entity()))
                .thenComparingDouble(
                        candidate ->
                                candidate.entity().getHealth()
                                        + candidate.entity().getAbsorptionAmount())
                .thenComparingInt(candidate -> candidate.entity().hurtTime)
                .thenComparingInt(candidate -> candidate.locked() ? 0 : 1)
                .thenComparingInt(candidate -> candidate.entity().getId());
    }

    private Vec3 visibleAimPoint(Minecraft client, LivingEntity target, Vec3 look, double range) {
        if (!validClient(client) || target == null || range <= 0.0D) return null;
        AABB aimBox = target.getBoundingBox();
        return VisibleAimPoints.findBestVisibleSurfacePoint(
                client,
                aimBox,
                look,
                range,
                UPPER_BODY_ANCHOR,
                lockMode,
                SilentAuraConfig.throughBlocks());
    }

    private Vec3 trackingAimPoint(Minecraft client, LivingEntity target, Vec3 look) {
        Vec3 eye = client.player.getEyePosition();
        AABB box = target.getBoundingBox();
        double trackingRange =
                inAttackRange(client, target) ? attackRange(client) : scanRange(client);

        if (fullLockMode) {
            Vec3 preferred = fullLockAimPoint(eye, box);
            double attackRange = attackRange(client);
            boolean attackReach =
                    attackRange > 0.0D
                            && EntityDistance.squaredToEntity(client, target)
                                    <= attackRange * attackRange;
            double activeRange = attackReach ? attackRange : trackingRange;
            if (eye.distanceToSqr(preferred) <= activeRange * activeRange
                    && RaytraceUtils.canRayTraceTo(
                            client, eye, preferred, SilentAuraConfig.throughBlocks())) {
                return preferred;
            }
            // FULL-Lock uses the centre corridor until it is covered. Re-scan the
            // complete hitbox in the same frame and choose an actually visible
            // surface point; while in attack reach, reject scan-only points.
            return visibleAimPoint(client, target, look, activeRange);
        }

        // Lock prioritises ray uptime.  Center/Closest still owns the vertical
        // policy, but when the confirmed packet ray misses we steer toward the
        // visible hitbox point with the smallest angular correction.  When it
        // already hits, retain that ray slightly inside the box instead of
        // allowing wander to pull the next packet back out.  This is strictly
        // target-point selection: packet acceleration/GCD and the final HIT
        // gate remain unchanged.
        Vec3 lockPoint =
                lockMode
                        ? lockRayRetentionPoint(client, target, eye, box, look, trackingRange)
                        : null;
        if (lockPoint != null) return lockPoint;

        Vec3 preferred;
        if (SilentAuraConfig.closestAimPoint()) {
            preferred =
                    aimPoints.closest(
                            client,
                            target,
                            eye,
                            box,
                            scanRange(client),
                            SilentAuraConfig.aimWanderTicks(),
                            SilentAuraConfig.throughBlocks());
        } else {
            preferred =
                    aimPoints.center(
                            client,
                            target,
                            eye,
                            box,
                            trackingRange,
                            SilentAuraConfig.aimWander(),
                            SilentAuraConfig.aimWanderTicks(),
                            !lockMode,
                            SilentAuraConfig.throughBlocks());
        }
        if (eye.distanceToSqr(preferred) <= trackingRange * trackingRange
                && RaytraceUtils.canRayTraceTo(
                        client, eye, preferred, SilentAuraConfig.throughBlocks())) {
            return preferred;
        }
        // The preferred upper-body point can be covered while a leg remains
        // exposed. Search the complete visible surface and merely prefer, not
        // require, the configured upper-body height.
        return visibleAimPoint(client, target, look, trackingRange);
    }

    private Vec3 lockRayRetentionPoint(
            Minecraft client,
            LivingEntity target,
            Vec3 eye,
            AABB box,
            Vec3 look,
            double trackingRange) {
        if (look == null || look.lengthSqr() <= 1.0E-9D || box.contains(eye)) return null;
        double attackRange = attackRange(client);
        if (attackRange <= 0.0D
                || EntityDistance.squaredToEntity(client, target) > attackRange * attackRange) {
            return null;
        }

        Vec3 direction = look.normalize();
        RaytraceUtils.EntityRayState state =
                RaytraceUtils.traceEntity(
                        client,
                        eye,
                        direction,
                        attackRange,
                        target,
                        SilentAuraConfig.throughBlocks());
        Vec3 policyPoint =
                SilentAuraConfig.closestAimPoint()
                        ? aimPoints.closest(
                                client,
                                target,
                                eye,
                                box,
                                scanRange(client),
                                SilentAuraConfig.aimWanderTicks(),
                                SilentAuraConfig.throughBlocks())
                        : AimPointUtils.centerTrackingPoint(eye, box);

        if (state == RaytraceUtils.EntityRayState.HIT) {
            var intersection = box.clip(eye, eye.add(direction.scale(attackRange)));
            if (intersection.isEmpty()) return null;
            Vec3 retained = intersection.get().lerp(policyPoint, 0.20D);
            return RaytraceUtils.canRayTraceTo(
                            client, eye, retained, SilentAuraConfig.throughBlocks())
                    ? retained
                    : intersection.get();
        }
        if (state != RaytraceUtils.EntityRayState.AIM) return null;

        Vec3 angularRecovery =
                visibleAimPoint(client, target, direction, Math.min(trackingRange, attackRange));
        if (angularRecovery == null) return null;
        // Keep Center's eye-height Y (or Closest's configured Y) while taking
        // only the angularly-nearest surface X/Z from the recovery sampler.
        Vec3 policyRecovery = new Vec3(angularRecovery.x, policyPoint.y, angularRecovery.z);
        return eye.distanceToSqr(policyRecovery) <= attackRange * attackRange
                        && RaytraceUtils.canRayTraceTo(
                                client, eye, policyRecovery, SilentAuraConfig.throughBlocks())
                ? policyRecovery
                : angularRecovery;
    }

    /** Box-centre X/Z and 5%-75% vertical aim corridor. */
    private static Vec3 fullLockAimPoint(Vec3 eye, AABB box) {
        double height = box.maxY - box.minY;
        double minimumY = box.minY + height * 0.05D;
        double maximumY = box.minY + height * 0.75D;
        return new Vec3(
                (box.minX + box.maxX) * 0.5D,
                Mth.clamp(eye.y, minimumY, maximumY),
                (box.minZ + box.maxZ) * 0.5D);
    }

    private double angle(Minecraft client, LivingEntity entity) {
        return viewAngle(client, entity.getBoundingBox().getCenter());
    }

    private static int typeRank(Minecraft client, LivingEntity entity) {
        if (entity instanceof Player) return 0;
        if (entity instanceof Enemy) return 1;
        if (entity instanceof NeutralMob neutral
                && client.player.getUUID().equals(neutral.getPersistentAngerTarget())) {
            return 2;
        }
        return 3;
    }

    private static double viewAngle(Minecraft client, Vec3 point) {
        return RotationUtils.viewAngle(
                client.player.getEyePosition(), client.player.getViewVector(1.0F), point);
    }

    private static boolean validClient(Minecraft client) {
        return client != null && client.player != null && client.level != null;
    }

    private static double attackRange(Minecraft client) {
        return CombatReach.entityInteractionRange(client, SilentAuraConfig.aimRange());
    }

    private static double scanRange(Minecraft client) {
        return CombatReach.scanRange(
                client, SilentAuraConfig.aimRange(), SilentAuraConfig.scanExtra());
    }

    private record Candidate(
            LivingEntity entity,
            Vec3 point,
            double distanceSquared,
            boolean attackable,
            boolean locked) {}
}
