package com.blanoir.tempos.client.module.impl.world;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.function.BiPredicate;

/** Selects an escape block only while obsidian actually intersects the player's body. */
final class AntiObsidian {
    enum Target {
        AUTO,
        BELOW,
        FRONT
    }

    private static final double EPSILON = 1.0E-5;

    private AntiObsidian() {}

    static boolean obsidian(BlockState state) {
        return state.is(Blocks.OBSIDIAN) || state.is(Blocks.CRYING_OBSIDIAN);
    }

    static boolean trapped(BlockGetter world, AABB body) {
        AABB inside = body.deflate(EPSILON);
        for (BlockPos pos :
                BlockPos.betweenClosed(
                        BlockPos.containing(inside.minX, inside.minY, inside.minZ),
                        BlockPos.containing(inside.maxX, inside.maxY, inside.maxZ))) {
            if (obsidian(world.getBlockState(pos))) return true;
        }
        return false;
    }

    static BlockHitResult target(
            BlockGetter world,
            Entity source,
            AABB body,
            Vec3 eye,
            Vec3 look,
            double range,
            Target mode,
            BiPredicate<BlockState, BlockPos> ignored) {
        return target(world, source, body, eye, look, range, mode, ignored, null);
    }

    static BlockHitResult target(
            BlockGetter world,
            Entity source,
            AABB body,
            Vec3 eye,
            Vec3 look,
            double range,
            Target mode,
            BiPredicate<BlockState, BlockPos> ignored,
            BlockPos preferredSupport) {
        if (!Double.isFinite(range) || range <= 0 || !trapped(world, body)) return null;
        BlockHitResult below =
                mode == Target.FRONT ? null : below(world, body, eye, range, preferredSupport);
        if (mode == Target.BELOW) return below;
        if (below != null && !obsidian(world.getBlockState(below.getBlockPos()))) return below;
        Vec3 end = eye.add(look.normalize().scale(range));
        AABB inside = body.deflate(EPSILON);
        BlockHitResult front =
                Ignore.clip(
                        world,
                        source,
                        eye,
                        end,
                        (state, pos) ->
                                ignored.test(state, pos)
                                        || obsidian(state) && new AABB(pos).intersects(inside));
        if (front.getType() == HitResult.Type.BLOCK
                && world.getBlockState(front.getBlockPos())
                                .getDestroySpeed(world, front.getBlockPos())
                        >= 0) return front;
        return below;
    }

    private static BlockHitResult below(
            BlockGetter world, AABB body, Vec3 eye, double range, BlockPos preferredSupport) {
        BlockHitResult best = null;
        double nearest = range * range;
        int lowest = Mth.floor(body.minY) - 1;
        for (int y = Mth.floor(body.minY - EPSILON); y >= lowest; y--) {
            for (int x = Mth.floor(body.minX + EPSILON); x <= Mth.floor(body.maxX - EPSILON); x++) {
                for (int z = Mth.floor(body.minZ + EPSILON);
                        z <= Mth.floor(body.maxZ - EPSILON);
                        z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = world.getBlockState(pos);
                    if (state.isAir() || state.getDestroySpeed(world, pos) < 0) continue;
                    for (AABB shape : state.getCollisionShape(world, pos).toAabbs()) {
                        double top = y + shape.maxY;
                        double minX = Math.max(body.minX, x + shape.minX);
                        double maxX = Math.min(body.maxX, x + shape.maxX);
                        double minZ = Math.max(body.minZ, z + shape.minZ);
                        double maxZ = Math.min(body.maxZ, z + shape.maxZ);
                        if (top > body.minY + EPSILON || minX >= maxX || minZ >= maxZ) continue;
                        Vec3 hit = new Vec3((minX + maxX) * .5, top, (minZ + maxZ) * .5);
                        double distance = eye.distanceToSqr(hit);
                        if (pos.equals(preferredSupport) && distance <= range * range)
                            return new BlockHitResult(hit, Direction.UP, pos, false);
                        if (distance <= nearest) {
                            nearest = distance;
                            best = new BlockHitResult(hit, Direction.UP, pos, false);
                        }
                    }
                }
            }
        }
        return best;
    }
}
