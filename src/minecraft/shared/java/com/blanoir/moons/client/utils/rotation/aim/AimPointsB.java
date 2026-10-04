package com.blanoir.moons.client.utils.rotation.aim;

import com.blanoir.moons.client.utils.entity.EntityDistance;
import com.blanoir.moons.client.utils.math.MathUtils;
import com.blanoir.moons.client.utils.math.RandomMath;
import com.blanoir.moons.client.utils.raytrace.RaytraceUtils;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.Vec3;

/**
 * B: Nearest-to-eye geometry and target-local held anchors. Used by AimAssist and SilentAura.
 * resolve mutates only the supplied State and samples a hold duration on refresh.
 * closestTrackingPoint is a stateless probe; ray-nearest selection is F. Reset history at the owning mode's
 * existing reset boundary; do not share it between modes.
 */
public final class AimPointsB {
    private AimPointsB() {}

    private static final double UPPER_BODY_ANCHOR = 0.72D;

    /** Caller-owned hold history; probing geometry alone never advances it. */
    public static final class State {
        public int closestAnchorTargetId = -1;
        public int nextClosestAnchorTick = Integer.MIN_VALUE;
        public double closestFractionX = 0.5D;
        public double closestFractionY = UPPER_BODY_ANCHOR;
        public double closestFractionZ = 0.5D;

        public void reset() {
            closestAnchorTargetId = -1;
            nextClosestAnchorTick = Integer.MIN_VALUE;
        }
    }

    public static Vec3 resolve(
            State state,
            Minecraft client,
            EntityLivingBase target,
            Vec3 eye,
            AxisAlignedBB box,
            double trackingRange,
            int wanderTicks) {
        return resolve(state, client, target, eye, box, trackingRange, wanderTicks, false);
    }

    public static Vec3 resolve(
            State state,
            Minecraft client,
            EntityLivingBase target,
            Vec3 eye,
            AxisAlignedBB box,
            double trackingRange,
            int wanderTicks,
            boolean throughBlocks) {

        int tick = client.thePlayer.ticksExisted;
        Vec3 held =
                AimGeometry.localPoint(
                        box,
                        state.closestFractionX,
                        state.closestFractionY,
                        state.closestFractionZ);
        boolean usableHeld =
                state.closestAnchorTargetId == target.getEntityId()
                        && eye.squareDistanceTo(held) <= trackingRange * trackingRange
                        && RaytraceUtils.canRayTraceTo(client, eye, held, throughBlocks);
        if (!usableHeld || tick >= state.nextClosestAnchorTick) {
            Vec3 closest = AimPointsB.closestTrackingPoint(eye, box);
            state.closestAnchorTargetId = target.getEntityId();
            state.closestFractionX = AimGeometry.fraction(closest.xCoord, box.minX, box.maxX);
            state.closestFractionY = AimGeometry.fraction(closest.yCoord, box.minY, box.maxY);
            state.closestFractionZ = AimGeometry.fraction(closest.zCoord, box.minZ, box.maxZ);
            int minimumHold = Math.max(3, wanderTicks / 2);
            int maximumHold = Math.max(minimumHold + 1, wanderTicks);
            state.nextClosestAnchorTick =
                    tick + RandomMath.betweenInclusive(minimumHold, maximumHold);
            held = closest;
        }
        return held;
    }

    public static Vec3 closestTrackingPoint(Vec3 eye, AxisAlignedBB box) {
        return EntityDistance.closestPoint(eye, MathUtils.inset(box, 0.055D, 0.18D));
    }
}
