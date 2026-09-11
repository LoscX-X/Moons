package com.blanoir.moons.client.utils.world.placement;

import com.blanoir.moons.client.utils.rotation.Rotation;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Stateless block-face geometry and ray-tracing helpers. */
public final class BlockPlacementUtils {
    private BlockPlacementUtils() {}

    /** A full block face using offsets from its center on the two varying axes. */
    public static Vec3 fullBlockFaceOffset(
            BlockPos block, Direction face, double first, double second) {
        double x = block.getX() + 0.5D;
        double y = block.getY() + 0.5D;
        double z = block.getZ() + 0.5D;
        return switch (face.getAxis()) {
            case X -> new Vec3(x + face.getStepX() * 0.5D, y + first, z + second);
            case Y -> new Vec3(x + first, y + face.getStepY() * 0.5D, z + second);
            case Z -> new Vec3(x + first, y + second, z + face.getStepZ() * 0.5D);
        };
    }

    /** A full block face using coordinates relative to the block's minimum corner. */
    public static Vec3 fullBlockFacePoint(
            BlockPos block, Direction face, double first, double second) {
        double x = block.getX();
        double y = block.getY();
        double z = block.getZ();
        return switch (face.getAxis()) {
            case X -> new Vec3(x + (face == Direction.EAST ? 1.0D : 0.0D), y + first, z + second);
            case Y -> new Vec3(x + first, y + (face == Direction.UP ? 1.0D : 0.0D), z + second);
            case Z -> new Vec3(x + first, y + second, z + (face == Direction.SOUTH ? 1.0D : 0.0D));
        };
    }

    public static BlockHitResult visibleFaceHit(
            Minecraft client,
            Vec3 eye,
            BlockPos support,
            Direction face,
            Vec3 requested,
            double epsilon) {
        Vec3 justInside =
                requested.add(
                        -face.getStepX() * epsilon,
                        -face.getStepY() * epsilon,
                        -face.getStepZ() * epsilon);
        BlockHitResult hit =
                client.level.clip(
                        new ClipContext(
                                eye,
                                justInside,
                                ClipContext.Block.OUTLINE,
                                ClipContext.Fluid.NONE,
                                client.player));
        if (!matchesFace(hit, support, face)) {
            return null;
        }
        return new BlockHitResult(hit.getLocation(), face, support, hit.isInside());
    }

    /** Traces an interaction direction without normalizing it or choosing a rotation owner. */
    public static BlockHitResult traceOutline(
            Minecraft client, Vec3 eye, Vec3 look, double range, ClipContext.Fluid fluid) {
        Vec3 end = eye.add(look.scale(range));
        return client.level.clip(
                new ClipContext(eye, end, ClipContext.Block.OUTLINE, fluid, client.player));
    }

    public static BlockHitResult traceOutline(
            Minecraft client, Rotation rotation, double range, ClipContext.Fluid fluid) {
        return traceOutline(
                client,
                client.player.getEyePosition(1.0F),
                Vec3.directionFromRotation(rotation.pitch(), rotation.yaw()),
                range,
                fluid);
    }

    public static boolean solidWithoutMenu(Minecraft client, BlockPos pos) {
        BlockState state = client.level.getBlockState(pos);
        return !state.getCollisionShape(client.level, pos).isEmpty()
                && state.getMenuProvider(client.level, pos) == null;
    }

    public static boolean matchesBlock(BlockHitResult hit, BlockPos block) {
        return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(block);
    }

    public static boolean matchesFace(BlockHitResult hit, BlockPos block, Direction face) {
        return matchesBlock(hit, block) && hit.getDirection() == face;
    }

    /** Does not inspect the planned hit after a miss or a different support block. */
    public static boolean matchesFace(BlockHitResult hit, BlockHitResult planned) {
        return hit.getType() == HitResult.Type.BLOCK
                && hit.getBlockPos().equals(planned.getBlockPos())
                && hit.getDirection() == planned.getDirection();
    }

    /** Point distance, not distance to a block's nearest box surface. */
    public static boolean withinReach(Vec3 eye, Vec3 point, double range) {
        return eye.distanceToSqr(point) <= range * range;
    }

    /** Shared shape condition; item exclusions and material preference stay with the feature. */
    public static boolean hasSolidPlacementShape(BlockGetter level, BlockState state) {
        return !state.canBeReplaced() && !state.getCollisionShape(level, BlockPos.ZERO).isEmpty();
    }

    public static Vec3 facePoint(
            Minecraft client, BlockPos support, Direction face, double u, double v) {
        BlockState state = client.level.getBlockState(support);
        AABB bounds;
        try {
            bounds = state.getShape(client.level, support).bounds();
        } catch (RuntimeException ignored) {
            bounds = new AABB(0.0D, 0.0D, 0.0D, 1.0D, 1.0D, 1.0D);
        }
        double x = support.getX() + Mth.lerp(u, bounds.minX, bounds.maxX);
        double y = support.getY() + Mth.lerp(v, bounds.minY, bounds.maxY);
        return switch (face) {
            case DOWN ->
                    new Vec3(
                            x,
                            support.getY() + bounds.minY,
                            support.getZ() + Mth.lerp(v, bounds.minZ, bounds.maxZ));
            case UP ->
                    new Vec3(
                            x,
                            support.getY() + bounds.maxY,
                            support.getZ() + Mth.lerp(v, bounds.minZ, bounds.maxZ));
            case NORTH -> new Vec3(x, y, support.getZ() + bounds.minZ);
            case EAST ->
                    new Vec3(
                            support.getX() + bounds.maxX,
                            y,
                            support.getZ() + Mth.lerp(u, bounds.minZ, bounds.maxZ));
            case SOUTH -> new Vec3(x, y, support.getZ() + bounds.maxZ);
            case WEST ->
                    new Vec3(
                            support.getX() + bounds.minX,
                            y,
                            support.getZ() + Mth.lerp(u, bounds.minZ, bounds.maxZ));
        };
    }

    public static BlockHitResult traceFace(
            Minecraft client,
            Vec3 eye,
            float yaw,
            float pitch,
            double range,
            BlockPos support,
            Direction face) {
        Vec3 end = eye.add(Vec3.directionFromRotation(pitch, yaw).scale(range));
        BlockHitResult hit =
                client.level.clip(
                        new ClipContext(
                                eye,
                                end,
                                ClipContext.Block.OUTLINE,
                                ClipContext.Fluid.NONE,
                                client.player));
        return hit.getType() == HitResult.Type.BLOCK
                        && hit.getBlockPos().equals(support)
                        && hit.getDirection() == face
                ? hit
                : null;
    }
}
