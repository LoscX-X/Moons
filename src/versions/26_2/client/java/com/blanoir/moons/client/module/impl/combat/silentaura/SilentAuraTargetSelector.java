package com.blanoir.moons.client.module.impl.combat.silentaura;

import com.blanoir.moons.client.management.targeting.Targeting;
import com.blanoir.moons.client.utils.rotation.aim.RotationUtils;
import com.blanoir.moons.client.utils.rotation.aim.VisibleAimPoints;
import com.blanoir.moons.client.utils.combat.CombatReach;
import com.blanoir.moons.client.utils.entity.EntityDistance;
import com.blanoir.moons.client.utils.math.RandomMath;
import com.blanoir.moons.client.utils.math.MathUtils;
import com.blanoir.moons.client.utils.raytrace.RaytraceUtils;
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

/** LiquidBounce-style target tracking and box-surface aim-point resolution. */
public final class SilentAuraTargetSelector {
    /** Closest still aims near the eye-facing side, but never at a fragile box edge. */
    private static final double CLOSEST_INSET = 0.055D;
    /** Normal combat aim stays between the upper chest and the head. */
    private static final double UPPER_BODY_FLOOR = 0.58D;
    private static final double UPPER_BODY_ANCHOR = 0.72D;

    private final boolean lockMode;
    private final boolean fullLockMode;

    private int targetId = -1;
    private int selectionTick = Integer.MIN_VALUE;
    private int anchorTargetId = -1;
    private int nextAnchorTick = Integer.MIN_VALUE;
    private double anchorFractionX = 0.5D;
    private double anchorFractionY = 0.5D;
    private double anchorFractionZ = 0.5D;
    private Vec3 wanderFrom;
    private int anchorStartTick;
    private int closestAnchorTargetId = -1;
    private int nextClosestAnchorTick = Integer.MIN_VALUE;
    private double closestFractionX = 0.5D;
    private double closestFractionY = UPPER_BODY_ANCHOR;
    private double closestFractionZ = 0.5D;
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
        if (!validClient(client)) { clear(); return null; }

        double attackRange = attackRange(client);
        double scanRange = scanRange(client);
        if (attackRange <= 0.0D || scanRange <= 0.0D) { clear(); return null; }

        LivingEntity locked = current(client);
        if (selectionTick == client.player.tickCount
                && trackingEligible(client, locked, scanRange)) {
            return locked;
        }
        selectionTick = client.player.tickCount;

        List<Candidate> candidates = new ArrayList<>();
        Candidate lockedCandidate = candidate(
                client, locked, referenceLook, attackRange, scanRange, true);
        if (lockedCandidate != null) candidates.add(lockedCandidate);

        AABB searchBox = client.player.getBoundingBox().inflate(scanRange + 1.0D);
        for (LivingEntity entity : client.level.getEntitiesOfClass(LivingEntity.class, searchBox,
                entity -> entity != locked && acquisitionEligible(client, entity, scanRange))) {
            Candidate candidate = candidate(
                    client, entity, referenceLook, attackRange, scanRange, false);
            if (candidate != null) candidates.add(candidate);
        }

        boolean hasAttackable = candidates.stream().anyMatch(Candidate::attackable);
        if (hasAttackable) candidates.removeIf(candidate -> !candidate.attackable());
        candidates.sort(candidateComparator(client));

        Candidate retained = locked == null ? null : candidates.stream()
                .filter(candidate -> candidate.entity() == locked)
                .findFirst().orElse(null);
        boolean reevaluateNow = SilentAuraConfig.switchTargetMode()
                && reevaluateAfterAttack
                && client.player.tickCount >= reevaluateTick;
        boolean keepCurrent = retained != null && !reevaluateNow;
        Candidate selected = keepCurrent ? retained
                : candidates.isEmpty() ? null : candidates.getFirst();
        if (reevaluateNow || retained == null) reevaluateAfterAttack = false;
        if (selected == null) { clear(); return null; }

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
        return range > 0.0D
                && EntityDistance.squaredToEntity(client, target) <= range * range;
    }

    public Vec3 aimPoint(Minecraft client, LivingEntity target, Vec3 look) {
        if (!validClient(client) || target == null) return null;
        // Never reuse a world-space aim point. Resolve it from the target's
        // current bounding box on every frame, including in balance mode.
        if (target.getId() == targetId
                && trackingEligible(client, target, scanRange(client))) {
            return trackingAimPoint(client, target, look);
        }
        double attackRange = attackRange(client);
        double range = EntityDistance.squaredToEntity(client, target) <= attackRange * attackRange
                ? attackRange : scanRange(client);
        return visibleAimPoint(client, target, look, range);
    }

    public void clear() {
        targetId = -1;
        selectionTick = Integer.MIN_VALUE;
        anchorTargetId = -1;
        wanderFrom = null;
        nextAnchorTick = Integer.MIN_VALUE;
        closestAnchorTargetId = -1;
        nextClosestAnchorTick = Integer.MIN_VALUE;
        reevaluateAfterAttack = false;
        reevaluateTick = Integer.MIN_VALUE;
    }

    /** High-version switch mode re-ranks only after a real cooldown-gated hit. */
    public void onAttack(Minecraft client, LivingEntity attacked) {
        if (!SilentAuraConfig.switchTargetMode() || attacked == null
                || attacked.getId() != targetId) return;
        reevaluateAfterAttack = true;
        reevaluateTick = client != null && client.player != null
                ? client.player.tickCount + 1 : Integer.MIN_VALUE;
        selectionTick = Integer.MIN_VALUE;
    }

    private Candidate candidate(
            Minecraft client,
            LivingEntity entity,
            Vec3 referenceLook,
            double attackRange,
            double scanRange,
            boolean locked
    ) {
        if (!trackingEligible(client, entity, scanRange)) return null;
        double distanceSquared = EntityDistance.squaredToEntity(client, entity);
        Vec3 point = distanceSquared <= attackRange * attackRange
                ? visibleAimPoint(client, entity, referenceLook, attackRange) : null;
        boolean attackable = point != null;
        if (point == null) point = visibleAimPoint(client, entity, referenceLook, scanRange);
        return point == null ? null : new Candidate(entity, point, distanceSquared, attackable, locked);
    }

    private boolean trackingEligible(Minecraft client, LivingEntity entity, double range) {
        if (entity == null) return false;
        if (!Targeting.isConfiguredTarget(
                client, entity, SilentAuraConfig.targetPlayers(), SilentAuraConfig.targetMobs(),
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
        return Comparator
                .comparingInt((Candidate candidate) -> typeRank(client, candidate.entity()))
                .thenComparingDouble(Candidate::distanceSquared)
                .thenComparingDouble(candidate -> angle(client, candidate.entity()))
                .thenComparingDouble(candidate -> candidate.entity().getHealth()
                        + candidate.entity().getAbsorptionAmount())
                .thenComparingInt(candidate -> candidate.entity().hurtTime)
                .thenComparingInt(candidate -> candidate.locked() ? 0 : 1)
                .thenComparingInt(candidate -> candidate.entity().getId());
    }

    private Vec3 visibleAimPoint(Minecraft client, LivingEntity target, Vec3 look, double range) {
        if (!validClient(client) || target == null || range <= 0.0D) return null;
        AABB aimBox = target.getBoundingBox();
        return VisibleAimPoints.findBestVisibleSurfacePoint(
                client, aimBox, look, range, UPPER_BODY_ANCHOR,
                lockMode);
    }

    private Vec3 trackingAimPoint(Minecraft client, LivingEntity target, Vec3 look) {
        Vec3 eye = client.player.getEyePosition();
        AABB box = target.getBoundingBox();
        double trackingRange = inAttackRange(client, target)
                ? attackRange(client) : scanRange(client);

        if (fullLockMode) {
            Vec3 preferred = fullLockAimPoint(eye, box);
            double attackRange = attackRange(client);
            boolean attackReach = attackRange > 0.0D
                    && EntityDistance.squaredToEntity(client, target)
                    <= attackRange * attackRange;
            double activeRange = attackReach ? attackRange : trackingRange;
            if (eye.distanceToSqr(preferred) <= activeRange * activeRange
                    && RaytraceUtils.canRayTraceTo(client, eye, preferred)) {
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
        Vec3 lockPoint = lockMode
                ? lockRayRetentionPoint(client, target, eye, box, look, trackingRange)
                : null;
        if (lockPoint != null) return lockPoint;

        Vec3 preferred;
        if (SilentAuraConfig.closestAimPoint()) {
            preferred = stableClosestPoint(client, target, eye, box);
        } else {
            // "Center" is the established humanized policy: the horizontal
            // point is pulled inward while Y follows the local eye height.
            Vec3 wander = wanderPoint(client, target, eye, box);
            if (wander != null && eye.distanceToSqr(wander) <= trackingRange * trackingRange) return wander;
            preferred = centerTrackingPoint(eye, box);
        }
        if (eye.distanceToSqr(preferred) <= trackingRange * trackingRange
                && RaytraceUtils.canRayTraceTo(client, eye, preferred)) {
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
            double trackingRange
    ) {
        if (look == null || look.lengthSqr() <= 1.0E-9D || box.contains(eye)) return null;
        double attackRange = attackRange(client);
        if (attackRange <= 0.0D
                || EntityDistance.squaredToEntity(client, target) > attackRange * attackRange) {
            return null;
        }

        Vec3 direction = look.normalize();
        RaytraceUtils.EntityRayState state = RaytraceUtils.traceEntity(
                client, eye, direction, attackRange, target);
        Vec3 policyPoint = SilentAuraConfig.closestAimPoint()
                ? stableClosestPoint(client, target, eye, box)
                : centerTrackingPoint(eye, box);

        if (state == RaytraceUtils.EntityRayState.HIT) {
            var intersection = box.clip(eye, eye.add(direction.scale(attackRange)));
            if (intersection.isEmpty()) return null;
            Vec3 retained = intersection.get().lerp(policyPoint, 0.20D);
            return RaytraceUtils.canRayTraceTo(client, eye, retained)
                    ? retained : intersection.get();
        }
        if (state != RaytraceUtils.EntityRayState.AIM) return null;

        Vec3 angularRecovery = visibleAimPoint(
                client, target, direction, Math.min(trackingRange, attackRange));
        if (angularRecovery == null) return null;
        // Keep Center's eye-height Y (or Closest's configured Y) while taking
        // only the angularly-nearest surface X/Z from the recovery sampler.
        Vec3 policyRecovery = new Vec3(
                angularRecovery.x, policyPoint.y, angularRecovery.z);
        return eye.distanceToSqr(policyRecovery) <= attackRange * attackRange
                && RaytraceUtils.canRayTraceTo(client, eye, policyRecovery)
                ? policyRecovery : angularRecovery;
    }

    /**
     * Closest-point geometry is extremely sensitive to movement of the local
     * eye: while circling a stationary box, a freshly resolved surface point
     * copies that strafe one-for-one. Keep the nearest safe point in target-
     * local coordinates for a short, irregular burst. The point still follows
     * the target's box, but local movement no longer becomes a synchronized
     * per-packet aim signal.
     */
    private Vec3 stableClosestPoint(
            Minecraft client,
            LivingEntity target,
            Vec3 eye,
            AABB box
    ) {
        AABB interior = MathUtils.inset(box, CLOSEST_INSET, 0.18D);
        int tick = client.player.tickCount;
        Vec3 held = localPoint(box, closestFractionX, closestFractionY, closestFractionZ);
        double trackingRange = scanRange(client);
        boolean usableHeld = closestAnchorTargetId == target.getId()
                && eye.distanceToSqr(held) <= trackingRange * trackingRange
                && RaytraceUtils.canRayTraceTo(client, eye, held);
        if (!usableHeld || tick >= nextClosestAnchorTick) {
            Vec3 closest = EntityDistance.closestPoint(eye, interior);
            closestAnchorTargetId = target.getId();
            closestFractionX = fraction(closest.x, box.minX, box.maxX);
            closestFractionY = fraction(closest.y, box.minY, box.maxY);
            closestFractionZ = fraction(closest.z, box.minZ, box.maxZ);
            int minimumHold = Math.max(3, SilentAuraConfig.aimWanderTicks() / 2);
            int maximumHold = Math.max(minimumHold + 1, SilentAuraConfig.aimWanderTicks());
            nextClosestAnchorTick = tick
                    + RandomMath.betweenInclusive(minimumHold, maximumHold);
            held = closest;
        }
        return held;
    }

    private static Vec3 localPoint(AABB box, double x, double y, double z) {
        return new Vec3(
                Mth.lerp(Mth.clamp(x, 0.0D, 1.0D), box.minX, box.maxX),
                Mth.lerp(Mth.clamp(y, 0.0D, 1.0D), box.minY, box.maxY),
                Mth.lerp(Mth.clamp(z, 0.0D, 1.0D), box.minZ, box.maxZ));
    }

    private static double fraction(double value, double minimum, double maximum) {
        double size = maximum - minimum;
        return size <= 1.0E-6D ? 0.5D
                : Mth.clamp((value - minimum) / size, 0.0D, 1.0D);
    }

    private static Vec3 centerTrackingPoint(Vec3 eye, AABB box) {
        // Preserve eye-height following: it removes artificial vertical head
        // motion on level ground and is part of Center's humanized behaviour.
        Vec3 closest = EntityDistance.closestPoint(eye, box);
        Vec3 inset = closest.lerp(box.getCenter(), 0.18D);
        double lowerAimY = Mth.lerp(UPPER_BODY_FLOOR, box.minY, box.maxY);
        double upperAimY = Mth.lerp(0.92D, box.minY, box.maxY);
        return new Vec3(inset.x, Mth.clamp(closest.y, lowerAimY, upperAimY), inset.z);
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

    /**
     * Redraws a random anchor inside the hitbox every few ticks so the tracked
     * point wanders around the body instead of sliding along one deterministic
     * surface track. Anchors that are out of range or occluded fall back to the
     * deterministic closest-point resolution.
     */
    private Vec3 wanderPoint(Minecraft client, LivingEntity target, Vec3 eye, AABB box) {
        double wander = SilentAuraConfig.aimWander();
        if (wander <= 0.0D) return null;
        int tick = client.player.tickCount;
        if (anchorTargetId != target.getId() || tick >= nextAnchorTick) {
            boolean changed = anchorTargetId != target.getId() || wanderFrom == null;
            Vec3 previous = changed ? null : wanderFractions(tick);
            anchorTargetId = target.getId();
            double spread = 0.5D * Mth.clamp(wander, 0.0D, 1.0D);
            anchorFractionX = RandomMath.between(0.5D - spread, 0.5D + spread);
            // Centre vertical wander on the closest (normally horizontal)
            // upper-body ray. It is an offset, not a random head-to-feet pick.
            double rawEyeFractionY =
                    (eye.y - box.minY) / Math.max(box.getYsize(), 0.1D);
            double closestFractionY = Mth.clamp(
                    rawEyeFractionY, UPPER_BODY_FLOOR, 0.90D);
            double verticalSpread = spread * 0.18D;
            boolean balanceCanStayLevel = !lockMode
                    && rawEyeFractionY >= UPPER_BODY_FLOOR
                    && rawEyeFractionY <= 0.90D;
            anchorFractionY = balanceCanStayLevel
                    ? closestFractionY
                    : RandomMath.between(
                    Math.max(UPPER_BODY_FLOOR, closestFractionY - verticalSpread),
                    Math.min(0.90D, closestFractionY + verticalSpread));
            anchorFractionZ = RandomMath.between(0.5D - spread, 0.5D + spread);
            int duration = Math.max(2, SilentAuraConfig.aimWanderTicks());
            anchorStartTick = tick;
            nextAnchorTick = tick + RandomMath.betweenInclusive(
                    Math.max(2, (int) Math.round(duration * 0.65D)),
                    Math.max(3, (int) Math.round(duration * 1.35D)));
            wanderFrom = changed
                    ? new Vec3(anchorFractionX, anchorFractionY, anchorFractionZ) : previous;
        }
        Vec3 fractions = wanderFractions(tick);
        Vec3 anchor = localPoint(box, fractions.x, fractions.y, fractions.z);
        double trackingRange = scanRange(client);
        if (eye.distanceToSqr(anchor) > trackingRange * trackingRange) return null;
        return RaytraceUtils.canRayTraceTo(client, eye, anchor) ? anchor : null;
    }

    private Vec3 wanderFractions(int tick) {
        double progress = Mth.clamp((double) (tick - anchorStartTick)
                / Math.max(1, nextAnchorTick - anchorStartTick), 0.0D, 1.0D);
        double blend = progress * progress * progress
                * (progress * (progress * 6.0D - 15.0D) + 10.0D);
        return wanderFrom.lerp(new Vec3(anchorFractionX, anchorFractionY, anchorFractionZ), blend);
    }

    private double angle(Minecraft client, LivingEntity entity) {
        return viewAngle(client, entity.getBoundingBox().getCenter());
    }

    private static int typeRank(Minecraft client, LivingEntity entity) {
        if (entity instanceof Player) return 0;
        if (entity instanceof Enemy) return 1;
        if (entity instanceof NeutralMob neutral && client.player.getUUID().equals(neutral.getPersistentAngerTarget())) {
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
            boolean locked
    ) {}
}
