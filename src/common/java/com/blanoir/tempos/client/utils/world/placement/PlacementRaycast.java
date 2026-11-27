package com.blanoir.tempos.client.utils.world.placement;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Intersections used by the placement ray policies. */
public final class PlacementRaycast {
    private PlacementRaycast() {}

    public static boolean blocksSegment(AABB box, Vec3 eye, Vec3 end, double distanceSquared) {
        return box.contains(eye)
                || box.clip(eye, end)
                        .map(point -> eye.distanceToSqr(point) + 1.0E-7 < distanceSquared)
                        .orElse(false);
    }

    public static BlockHitResult clipBlocks(
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
}
