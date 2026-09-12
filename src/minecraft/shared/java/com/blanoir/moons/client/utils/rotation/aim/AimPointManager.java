package com.blanoir.moons.client.utils.rotation.aim;

import com.blanoir.moons.client.utils.math.RandomMath;
import com.blanoir.moons.client.utils.raytrace.RaytraceUtils;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Owns target-local Center/Closest anchors for one feature's selected target. */
public final class AimPointManager {
    private static final double UPPER_BODY_FLOOR = 0.58D;
    private static final double UPPER_BODY_ANCHOR = 0.72D;
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

    public void reset() {
        anchorTargetId = closestAnchorTargetId = -1;
        nextAnchorTick = nextClosestAnchorTick = Integer.MIN_VALUE;
        wanderFrom = null;
    }

    public Vec3 center(
            Minecraft client,
            LivingEntity target,
            Vec3 eye,
            AABB box,
            double range,
            double wander,
            int wanderTicks,
            boolean keepLevel) {
        return center(client, target, eye, box, range, wander, wanderTicks, keepLevel, false);
    }

    public Vec3 center(
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
                        client,
                        target,
                        eye,
                        box,
                        range,
                        wander,
                        wanderTicks,
                        keepLevel,
                        throughBlocks);
        return anchor != null ? anchor : AimPointUtils.centerTrackingPoint(eye, box);
    }

    public Vec3 closest(
            Minecraft client,
            LivingEntity target,
            Vec3 eye,
            AABB box,
            double trackingRange,
            int wanderTicks) {
        return closest(client, target, eye, box, trackingRange, wanderTicks, false);
    }

    public Vec3 closest(
            Minecraft client,
            LivingEntity target,
            Vec3 eye,
            AABB box,
            double trackingRange,
            int wanderTicks,
            boolean throughBlocks) {

        int tick = client.player.tickCount;
        Vec3 held =
                AimPointUtils.localPoint(box, closestFractionX, closestFractionY, closestFractionZ);
        boolean usableHeld =
                closestAnchorTargetId == target.getId()
                        && eye.distanceToSqr(held) <= trackingRange * trackingRange
                        && RaytraceUtils.canRayTraceTo(client, eye, held, throughBlocks);
        if (!usableHeld || tick >= nextClosestAnchorTick) {
            Vec3 closest = AimPointUtils.closestTrackingPoint(eye, box);
            closestAnchorTargetId = target.getId();
            closestFractionX = AimPointUtils.fraction(closest.x, box.minX, box.maxX);
            closestFractionY = AimPointUtils.fraction(closest.y, box.minY, box.maxY);
            closestFractionZ = AimPointUtils.fraction(closest.z, box.minZ, box.maxZ);
            int minimumHold = Math.max(3, wanderTicks / 2);
            int maximumHold = Math.max(minimumHold + 1, wanderTicks);
            nextClosestAnchorTick = tick + RandomMath.betweenInclusive(minimumHold, maximumHold);
            held = closest;
        }
        return held;
    }

    private Vec3 wanderPoint(
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
        if (anchorTargetId != target.getId() || tick >= nextAnchorTick) {
            boolean changed = anchorTargetId != target.getId() || wanderFrom == null;
            Vec3 previous = changed ? null : wanderFractions(tick);
            anchorTargetId = target.getId();
            double spread = 0.5D * Mth.clamp(wander, 0.0D, 1.0D);
            anchorFractionX = RandomMath.between(0.5D - spread, 0.5D + spread);
            // Centre vertical wander on the closest (normally horizontal)
            // upper-body ray. It is an offset, not a random head-to-feet pick.
            double rawEyeFractionY = (eye.y - box.minY) / Math.max(box.getYsize(), 0.1D);
            double closestFractionY = Mth.clamp(rawEyeFractionY, UPPER_BODY_FLOOR, 0.90D);
            double verticalSpread = spread * 0.18D;
            boolean balanceCanStayLevel =
                    keepLevel && rawEyeFractionY >= UPPER_BODY_FLOOR && rawEyeFractionY <= 0.90D;
            anchorFractionY =
                    balanceCanStayLevel
                            ? closestFractionY
                            : RandomMath.between(
                                    Math.max(UPPER_BODY_FLOOR, closestFractionY - verticalSpread),
                                    Math.min(0.90D, closestFractionY + verticalSpread));
            anchorFractionZ = RandomMath.between(0.5D - spread, 0.5D + spread);
            int duration = Math.max(2, wanderTicks);
            anchorStartTick = tick;
            nextAnchorTick =
                    tick
                            + RandomMath.betweenInclusive(
                                    Math.max(2, (int) Math.round(duration * 0.65D)),
                                    Math.max(3, (int) Math.round(duration * 1.35D)));
            wanderFrom =
                    changed
                            ? new Vec3(anchorFractionX, anchorFractionY, anchorFractionZ)
                            : previous;
        }
        Vec3 fractions = wanderFractions(tick);
        Vec3 anchor = AimPointUtils.localPoint(box, fractions.x, fractions.y, fractions.z);
        if (eye.distanceToSqr(anchor) > trackingRange * trackingRange) return null;
        return RaytraceUtils.canRayTraceTo(client, eye, anchor, throughBlocks) ? anchor : null;
    }

    private Vec3 wanderFractions(int tick) {
        double progress =
                Mth.clamp(
                        (double) (tick - anchorStartTick)
                                / Math.max(1, nextAnchorTick - anchorStartTick),
                        0.0D,
                        1.0D);
        double blend =
                progress * progress * progress * (progress * (progress * 6.0D - 15.0D) + 10.0D);
        return wanderFrom.lerp(new Vec3(anchorFractionX, anchorFractionY, anchorFractionZ), blend);
    }
}
