package com.blanoir.moons.client.utils.rotation.aim;

import com.blanoir.moons.client.utils.entity.EntityDistance;
import com.blanoir.moons.client.utils.math.RandomMath;
import com.blanoir.moons.client.utils.raytrace.RaytraceUtils;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * A: Center geometry and smooth target-local wandering. Used by AimAssist and SilentAura.
 * resolve advances only the supplied State and samples random values at the original anchor
 * refresh points. It returns the tracking center when the wandering point is unusable.
 * Invoke at the owning mode's existing cadence; candidate scans use centerTrackingPoint only.
 */
public final class AimPointsA {
    private AimPointsA() {}

    private static final double UPPER_BODY_FLOOR = 0.58D;
    private static final double UPPER_BODY_ANCHOR = 0.72D;

    /** One mode owns one instance; reset on the original target/context lifecycle. */
    public static final class State {
        public int anchorTargetId = -1;
        public int nextAnchorTick = Integer.MIN_VALUE;
        public double anchorFractionX = 0.5D;
        public double anchorFractionY = 0.5D;
        public double anchorFractionZ = 0.5D;
        public Vec3 wanderFrom;
        public int anchorStartTick;

        public void reset() {
            anchorTargetId = -1;
            nextAnchorTick = Integer.MIN_VALUE;
            wanderFrom = null;
        }
    }

    public static Vec3 resolve(
            State state,
            Minecraft client,
            LivingEntity target,
            Vec3 eye,
            AABB box,
            double range,
            double wander,
            int wanderTicks,
            boolean keepLevel) {
        return resolve(
                state, client, target, eye, box, range, wander, wanderTicks, keepLevel, false);
    }

    public static Vec3 resolve(
            State state,
            Minecraft client,
            LivingEntity target,
            Vec3 eye,
            AABB box,
            double range,
            double wander,
            int wanderTicks,
            boolean keepLevel,
            boolean throughBlocks) {
        Vec3 anchor =
                wanderPoint(
                        state,
                        client,
                        target,
                        eye,
                        box,
                        range,
                        wander,
                        wanderTicks,
                        keepLevel,
                        throughBlocks);
        return anchor != null ? anchor : AimPointsA.centerTrackingPoint(eye, box);
    }

    private static Vec3 wanderPoint(
            State state,
            Minecraft client,
            LivingEntity target,
            Vec3 eye,
            AABB box,
            double trackingRange,
            double wander,
            int wanderTicks,
            boolean keepLevel,
            boolean throughBlocks) {
        if (wander <= 0.0D) return null;
        int tick = client.player.tickCount;
        if (state.anchorTargetId != target.getId() || tick >= state.nextAnchorTick) {
            boolean changed = state.anchorTargetId != target.getId() || state.wanderFrom == null;
            Vec3 previous = changed ? null : wanderFractions(state, tick);
            state.anchorTargetId = target.getId();
            double spread = 0.5D * Mth.clamp(wander, 0.0D, 1.0D);
            state.anchorFractionX = RandomMath.between(0.5D - spread, 0.5D + spread);
            // Centre vertical wander on the closest (normally horizontal)
            // upper-body ray. It is an offset, not a random head-to-feet pick.
            double rawEyeFractionY = (eye.y - box.minY) / Math.max(box.getYsize(), 0.1D);
            double closestFractionY = Mth.clamp(rawEyeFractionY, UPPER_BODY_FLOOR, 0.90D);
            double verticalSpread = spread * 0.18D;
            boolean balanceCanStayLevel =
                    keepLevel && rawEyeFractionY >= UPPER_BODY_FLOOR && rawEyeFractionY <= 0.90D;
            state.anchorFractionY =
                    balanceCanStayLevel
                            ? closestFractionY
                            : RandomMath.between(
                                    Math.max(UPPER_BODY_FLOOR, closestFractionY - verticalSpread),
                                    Math.min(0.90D, closestFractionY + verticalSpread));
            state.anchorFractionZ = RandomMath.between(0.5D - spread, 0.5D + spread);
            int duration = Math.max(2, wanderTicks);
            state.anchorStartTick = tick;
            state.nextAnchorTick =
                    tick
                            + RandomMath.betweenInclusive(
                                    Math.max(2, (int) Math.round(duration * 0.65D)),
                                    Math.max(3, (int) Math.round(duration * 1.35D)));
            state.wanderFrom =
                    changed
                            ? new Vec3(
                                    state.anchorFractionX,
                                    state.anchorFractionY,
                                    state.anchorFractionZ)
                            : previous;
        }
        Vec3 fractions = wanderFractions(state, tick);
        Vec3 anchor = AimGeometry.localPoint(box, fractions.x, fractions.y, fractions.z);
        if (eye.distanceToSqr(anchor) > trackingRange * trackingRange) return null;
        return RaytraceUtils.canRayTraceTo(client, eye, anchor, throughBlocks) ? anchor : null;
    }

    private static Vec3 wanderFractions(State state, int tick) {
        double progress =
                Mth.clamp(
                        (double) (tick - state.anchorStartTick)
                                / Math.max(1, state.nextAnchorTick - state.anchorStartTick),
                        0.0D,
                        1.0D);
        double blend =
                progress * progress * progress * (progress * (progress * 6.0D - 15.0D) + 10.0D);
        return state.wanderFrom.lerp(
                new Vec3(state.anchorFractionX, state.anchorFractionY, state.anchorFractionZ),
                blend);
    }

    public static Vec3 centerTrackingPoint(Vec3 eye, AABB box) {
        // Preserve eye-height following: it removes artificial vertical head
        // motion on level ground and is part of Center's humanized behaviour.
        Vec3 closest = EntityDistance.closestPoint(eye, box);
        Vec3 inset = closest.lerp(box.getCenter(), 0.18D);
        double lowerAimY = Mth.lerp(0.58D, box.minY, box.maxY);
        double upperAimY = Mth.lerp(0.92D, box.minY, box.maxY);
        return new Vec3(inset.x, Mth.clamp(closest.y, lowerAimY, upperAimY), inset.z);
    }

    public static Vec3 center(AABB box) {
        return center(box, 0.58D);
    }

    public static Vec3 center(AABB box, double verticalFactor) {
        return new Vec3(
                (box.minX + box.maxX) * 0.5D,
                Mth.lerp(Mth.clamp(verticalFactor, 0.0D, 1.0D), box.minY, box.maxY),
                (box.minZ + box.maxZ) * 0.5D);
    }
}
