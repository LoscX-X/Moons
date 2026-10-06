package com.blanoir.moons.client.utils.world.placement;

import com.blanoir.moons.client.compat.math.VecMath;
import com.blanoir.moons.client.config.Settings;
import com.blanoir.moons.client.manager.rotation.SilentPacketRotation;
import com.blanoir.moons.client.utils.rotation.Rotation;
import com.blanoir.moons.client.utils.world.LegacyRay;
import com.blanoir.moons.client.utils.world.LegacyWorld;

import net.minecraft.client.Minecraft;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraft.world.IBlockAccess;

/** Per-feature ray policy. Destination collision and item placement rules remain with vanilla. */
public final class PlacementRaycast {
    private static final ThreadLocal<MovingObjectPosition> ITEM_RAY = new ThreadLocal<>();
    private final String throughEntityKey;
    private final String legacyThroughEntityKey;
    private final String throughBlocksKey;

    public PlacementRaycast(String module) {
        throughEntityKey = module + ".throughentity";
        legacyThroughEntityKey = module + ".troughentity";
        throughBlocksKey = module + ".throughblocks";
    }

    public boolean throughEntity() {
        return Settings.getBoolean(
                throughEntityKey, Settings.getBoolean(legacyThroughEntityKey, false));
    }

    public boolean throughBlocks() {
        return Settings.getBoolean(throughBlocksKey, false);
    }

    public boolean entityBlocked(Minecraft client, Vec3 eye, Vec3 end) {
        if (throughEntity()) return false;
        double distance = eye.squareDistanceTo(end);
        for (var entity :
                client.theWorld.getEntitiesInAABBexcluding(
                        client.thePlayer,
                        LegacyWorld.inflate(LegacyWorld.box(eye, end), 1.0),
                        e ->
                                !(e instanceof net.minecraft.entity.player.EntityPlayer player
                                                && player.isSpectator())
                                        && e.canBeCollidedWith())) {
            AxisAlignedBB box =
                    LegacyWorld.inflate(
                            entity.getEntityBoundingBox(), entity.getCollisionBorderSize());
            if (blocksSegment(box, eye, end, distance)) return true;
        }
        return false;
    }

    static boolean blocksSegment(AxisAlignedBB box, Vec3 eye, Vec3 end, double distanceSquared) {
        return box.isVecInside(eye)
                || LegacyWorld.intercept(box, eye, end)
                        .map(point -> eye.squareDistanceTo(point) + 1.0E-7 < distanceSquared)
                        .orElse(false);
    }

    /** Trace just the specified target shape when wall traversal is enabled. */
    public MovingObjectPosition clip(
            Minecraft client, Vec3 eye, Vec3 end, LegacyRay.Fluid fluid, BlockPos target) {
        LegacyRay context =
                new LegacyRay(eye, end, LegacyRay.Block.OUTLINE, fluid, client.thePlayer);
        MovingObjectPosition hit = clipBlocks(client.theWorld, context, target, throughBlocks());
        if (hit == null || entityBlocked(client, eye, hit.hitVec)) {
            return LegacyWorld.miss(end, EnumFacing.UP, new BlockPos(end));
        }
        return hit;
    }

    static MovingObjectPosition clipBlocks(
            IBlockAccess level, LegacyRay context, BlockPos target, boolean throughBlocks) {
        Vec3 eye = context.getFrom();
        Vec3 end = context.getTo();
        MovingObjectPosition hit;
        if (throughBlocks && target != null) {
            var state = level.getBlockState(target);
            hit = context.getBlockShape(state, level, target).clip(eye, end, target);
            MovingObjectPosition liquid =
                    context.getFluidShape(LegacyWorld.fluid(state), level, target)
                            .clip(eye, end, target);
            if (liquid != null
                    && (hit == null
                            || eye.squareDistanceTo(liquid.hitVec)
                                    < eye.squareDistanceTo(hit.hitVec))) hit = liquid;
        } else {
            hit = LegacyWorld.clip(level, context);
        }
        return hit == null ? LegacyWorld.miss(end, EnumFacing.UP, new BlockPos(end)) : hit;
    }

    public MovingObjectPosition visibleFaceHit(
            Minecraft client,
            Vec3 eye,
            BlockPos support,
            EnumFacing face,
            Vec3 requested,
            double epsilon) {
        Vec3 end =
                requested.addVector(
                        -face.getFrontOffsetX() * epsilon,
                        -face.getFrontOffsetY() * epsilon,
                        -face.getFrontOffsetZ() * epsilon);
        MovingObjectPosition hit = clip(client, eye, end, LegacyRay.Fluid.NONE, support);
        return BlockPlacementUtils.matchesFace(hit, support, face) ? hit : null;
    }

    public MovingObjectPosition traceFace(
            Minecraft client,
            Vec3 eye,
            float yaw,
            float pitch,
            double range,
            BlockPos support,
            EnumFacing face) {
        MovingObjectPosition hit =
                traceOutline(
                        client,
                        eye,
                        VecMath.directionFromRotation(pitch, yaw),
                        range,
                        LegacyRay.Fluid.NONE,
                        support);
        return BlockPlacementUtils.matchesFace(hit, support, face) ? hit : null;
    }

    public MovingObjectPosition traceOutline(
            Minecraft client,
            Vec3 eye,
            Vec3 look,
            double range,
            LegacyRay.Fluid fluid,
            BlockPos target) {
        return clip(client, eye, eye.add(VecMath.scale(look, range)), fluid, target);
    }

    public MovingObjectPosition traceOutline(
            Minecraft client,
            Rotation rotation,
            double range,
            LegacyRay.Fluid fluid,
            BlockPos target) {
        return traceOutline(
                client,
                client.thePlayer.getPositionEyes(1.0F),
                VecMath.directionFromRotation(rotation.pitch(), rotation.yaw()),
                range,
                fluid,
                target);
    }

    public boolean canUse(Minecraft client, MovingObjectPosition planned) {
        if (planned == null
                || !BlockPlacementUtils.withinReach(
                        client.thePlayer.getPositionEyes(1.0F),
                        planned.hitVec,
                        Minecraft.getMinecraft().playerController.getBlockReachDistance()))
            return false;
        return visibleFaceHit(
                        client,
                        client.thePlayer.getPositionEyes(1.0F),
                        planned.getBlockPos(),
                        planned.sideHit,
                        planned.hitVec,
                        1.0E-4)
                != null;
    }

    public boolean invokeUseInPlayerUpdate(Minecraft client, MovingObjectPosition hit) {
        return invokeUseInPlayerUpdate(client, hit, true);
    }

    public boolean invokeUseInPlayerUpdate(
            Minecraft client, MovingObjectPosition hit, boolean requireSent) {
        if (hit == null) return false;
        boolean placement = hit.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK;
        MovingObjectPosition actual =
                traceOutline(
                        client,
                        client.thePlayer.getPositionEyes(1.0F),
                        SilentPacketRotation.getInteractionLookVector(client),
                        Minecraft.getMinecraft().playerController.getBlockReachDistance(),
                        placement ? LegacyRay.Fluid.NONE : LegacyRay.Fluid.SOURCE_ONLY,
                        hit.getBlockPos());
        if (!BlockPlacementUtils.matchesBlock(actual, hit.getBlockPos())
                || placement && actual.sideHit != hit.sideHit) return false;
        return SilentPacketRotation.invokeUseInPlayerUpdate(
                client, hit, requireSent, throughBlocks() ? actual : null);
    }

    /** The item ray is active only while the matching vanilla use invocation is on the stack. */
    public static void withItemRay(MovingObjectPosition hit, Runnable use) {
        MovingObjectPosition previous = ITEM_RAY.get();
        if (hit == null) ITEM_RAY.remove();
        else ITEM_RAY.set(hit);
        try {
            use.run();
        } finally {
            if (previous == null) ITEM_RAY.remove();
            else ITEM_RAY.set(previous);
        }
    }

    public static boolean hasItemRay() {
        return ITEM_RAY.get() != null;
    }

    public static Object itemRay(Object original) {
        MovingObjectPosition hit = ITEM_RAY.get();
        return hit == null ? original : hit;
    }
}
