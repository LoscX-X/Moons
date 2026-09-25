package com.blanoir.moons.client.utils.world.placement;

import com.blanoir.moons.client.config.Settings;
import com.blanoir.moons.client.management.rotation.SilentPacketRotation;
import com.blanoir.moons.client.utils.rotation.Rotation;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Per-feature ray policy. Destination collision and item placement rules remain with vanilla. */
public final class PlacementRaycast {
    private static final ThreadLocal<BlockHitResult> ITEM_RAY = new ThreadLocal<>();
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
        double distance = eye.distanceToSqr(end);
        for (var entity :
                client.level.getEntities(
                        client.player,
                        new AABB(eye, end).inflate(1.0),
                        e -> !e.isSpectator() && e.isPickable())) {
            AABB box = entity.getBoundingBox().inflate(entity.getPickRadius());
            if (blocksSegment(box, eye, end, distance)) return true;
        }
        return false;
    }

    static boolean blocksSegment(AABB box, Vec3 eye, Vec3 end, double distanceSquared) {
        return box.contains(eye)
                || box.clip(eye, end)
                        .map(point -> eye.distanceToSqr(point) + 1.0E-7 < distanceSquared)
                        .orElse(false);
    }

    /** Trace just the specified target shape when wall traversal is enabled. */
    public BlockHitResult clip(
            Minecraft client, Vec3 eye, Vec3 end, ClipContext.Fluid fluid, BlockPos target) {
        ClipContext context =
                new ClipContext(eye, end, ClipContext.Block.OUTLINE, fluid, client.player);
        BlockHitResult hit = clipBlocks(client.level, context, target, throughBlocks());
        if (hit == null || entityBlocked(client, eye, hit.getLocation())) {
            return BlockHitResult.miss(end, Direction.UP, BlockPos.containing(end));
        }
        return hit;
    }

    static BlockHitResult clipBlocks(
            BlockGetter level, ClipContext context, BlockPos target, boolean throughBlocks) {
        Vec3 eye = context.getFrom();
        Vec3 end = context.getTo();
        BlockHitResult hit;
        if (throughBlocks && target != null) {
            var state = level.getBlockState(target);
            hit = context.getBlockShape(state, level, target).clip(eye, end, target);
            BlockHitResult liquid =
                    context.getFluidShape(state.getFluidState(), level, target)
                            .clip(eye, end, target);
            if (liquid != null
                    && (hit == null
                            || eye.distanceToSqr(liquid.getLocation())
                                    < eye.distanceToSqr(hit.getLocation()))) hit = liquid;
        } else {
            hit = level.clip(context);
        }
        return hit == null ? BlockHitResult.miss(end, Direction.UP, BlockPos.containing(end)) : hit;
    }

    public BlockHitResult visibleFaceHit(
            Minecraft client,
            Vec3 eye,
            BlockPos support,
            Direction face,
            Vec3 requested,
            double epsilon) {
        Vec3 end =
                requested.add(
                        -face.getStepX() * epsilon,
                        -face.getStepY() * epsilon,
                        -face.getStepZ() * epsilon);
        BlockHitResult hit = clip(client, eye, end, ClipContext.Fluid.NONE, support);
        return BlockPlacementUtils.matchesFace(hit, support, face) ? hit : null;
    }

    public BlockHitResult traceFace(
            Minecraft client,
            Vec3 eye,
            float yaw,
            float pitch,
            double range,
            BlockPos support,
            Direction face) {
        BlockHitResult hit =
                traceOutline(
                        client,
                        eye,
                        Vec3.directionFromRotation(pitch, yaw),
                        range,
                        ClipContext.Fluid.NONE,
                        support);
        return BlockPlacementUtils.matchesFace(hit, support, face) ? hit : null;
    }

    public BlockHitResult traceOutline(
            Minecraft client,
            Vec3 eye,
            Vec3 look,
            double range,
            ClipContext.Fluid fluid,
            BlockPos target) {
        return clip(client, eye, eye.add(look.scale(range)), fluid, target);
    }

    public BlockHitResult traceOutline(
            Minecraft client,
            Rotation rotation,
            double range,
            ClipContext.Fluid fluid,
            BlockPos target) {
        return traceOutline(
                client,
                client.player.getEyePosition(1.0F),
                Vec3.directionFromRotation(rotation.pitch(), rotation.yaw()),
                range,
                fluid,
                target);
    }

    public boolean canUse(Minecraft client, BlockHitResult planned) {
        if (planned == null
                || !BlockPlacementUtils.withinReach(
                        client.player.getEyePosition(),
                        planned.getLocation(),
                        client.player.blockInteractionRange())) return false;
        return visibleFaceHit(
                        client,
                        client.player.getEyePosition(),
                        planned.getBlockPos(),
                        planned.getDirection(),
                        planned.getLocation(),
                        1.0E-4)
                != null;
    }

    public boolean invokeUseInPlayerUpdate(Minecraft client, BlockHitResult hit) {
        return invokeUseInPlayerUpdate(client, hit, true);
    }

    public boolean invokeUseInPlayerUpdate(
            Minecraft client, BlockHitResult hit, boolean requireSent) {
        if (hit == null) return false;
        boolean placement = hit.getType() == HitResult.Type.BLOCK;
        BlockHitResult actual =
                traceOutline(
                        client,
                        client.player.getEyePosition(),
                        SilentPacketRotation.getInteractionLookVector(client),
                        client.player.blockInteractionRange(),
                        placement ? ClipContext.Fluid.NONE : ClipContext.Fluid.SOURCE_ONLY,
                        hit.getBlockPos());
        if (!BlockPlacementUtils.matchesBlock(actual, hit.getBlockPos())
                || placement && actual.getDirection() != hit.getDirection()) return false;
        return SilentPacketRotation.invokeUseInPlayerUpdate(
                client, hit, requireSent, throughBlocks() ? actual : null);
    }

    /** The item ray is active only while the matching vanilla use invocation is on the stack. */
    public static void withItemRay(BlockHitResult hit, Runnable use) {
        BlockHitResult previous = ITEM_RAY.get();
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
        BlockHitResult hit = ITEM_RAY.get();
        return hit == null ? original : hit;
    }
}
