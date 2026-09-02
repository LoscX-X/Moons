package com.blanoir.moons.client.utils.world.placement;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Stateless block-face geometry and ray-tracing helpers. */
public final class BlockPlacementUtils {
    private BlockPlacementUtils() {
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
        double z = support.getZ() + Mth.lerp(u, bounds.minZ, bounds.maxZ);
        return switch (face) {
            case DOWN -> new Vec3(x, support.getY() + bounds.minY,
                    support.getZ() + Mth.lerp(v, bounds.minZ, bounds.maxZ));
            case UP -> new Vec3(x, support.getY() + bounds.maxY,
                    support.getZ() + Mth.lerp(v, bounds.minZ, bounds.maxZ));
            case NORTH -> new Vec3(x, y, support.getZ() + bounds.minZ);
            case EAST -> new Vec3(support.getX() + bounds.maxX, y,
                    support.getZ() + Mth.lerp(u, bounds.minZ, bounds.maxZ));
            case SOUTH -> new Vec3(x, y, support.getZ() + bounds.maxZ);
            case WEST -> new Vec3(support.getX() + bounds.minX, y,
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
            Direction face
    ) {
        Vec3 end = eye.add(Vec3.directionFromRotation(pitch, yaw).scale(range));
        BlockHitResult hit = client.level.clip(new ClipContext(
                eye, end, ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE, client.player));
        return hit.getType() == HitResult.Type.BLOCK
                && hit.getBlockPos().equals(support)
                && hit.getDirection() == face ? hit : null;
    }
}
