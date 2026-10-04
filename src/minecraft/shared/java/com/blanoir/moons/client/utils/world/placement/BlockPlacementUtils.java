package com.blanoir.moons.client.utils.world.placement;

import com.blanoir.moons.client.compat.math.Mth;
import com.blanoir.moons.client.compat.math.VecMath;
import com.blanoir.moons.client.utils.rotation.Rotation;
import com.blanoir.moons.client.utils.world.LegacyRay;
import com.blanoir.moons.client.utils.world.LegacyWorld;

import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraft.world.IBlockAccess;

/** Stateless block-face geometry and ray-tracing helpers. */
public final class BlockPlacementUtils {
    private BlockPlacementUtils() {}

    /** A full block face using offsets from its center on the two varying axes. */
    public static Vec3 fullBlockFaceOffset(
            BlockPos block, EnumFacing face, double first, double second) {
        double x = block.getX() + 0.5D;
        double y = block.getY() + 0.5D;
        double z = block.getZ() + 0.5D;
        return switch (face.getAxis()) {
            case X -> new Vec3(x + face.getFrontOffsetX() * 0.5D, y + first, z + second);
            case Y -> new Vec3(x + first, y + face.getFrontOffsetY() * 0.5D, z + second);
            case Z -> new Vec3(x + first, y + second, z + face.getFrontOffsetZ() * 0.5D);
        };
    }

    /** A full block face using coordinates relative to the block's minimum corner. */
    public static Vec3 fullBlockFacePoint(
            BlockPos block, EnumFacing face, double first, double second) {
        double x = block.getX();
        double y = block.getY();
        double z = block.getZ();
        return switch (face.getAxis()) {
            case X -> new Vec3(x + (face == EnumFacing.EAST ? 1.0D : 0.0D), y + first, z + second);
            case Y -> new Vec3(x + first, y + (face == EnumFacing.UP ? 1.0D : 0.0D), z + second);
            case Z -> new Vec3(x + first, y + second, z + (face == EnumFacing.SOUTH ? 1.0D : 0.0D));
        };
    }

    public static MovingObjectPosition visibleFaceHit(
            Minecraft client,
            Vec3 eye,
            BlockPos support,
            EnumFacing face,
            Vec3 requested,
            double epsilon) {
        Vec3 justInside =
                requested.addVector(
                        -face.getFrontOffsetX() * epsilon,
                        -face.getFrontOffsetY() * epsilon,
                        -face.getFrontOffsetZ() * epsilon);
        MovingObjectPosition hit =
                LegacyWorld.clip(
                        client.theWorld,
                        new LegacyRay(
                                eye,
                                justInside,
                                LegacyRay.Block.OUTLINE,
                                LegacyRay.Fluid.NONE,
                                client.thePlayer));
        if (!matchesFace(hit, support, face)) {
            return null;
        }
        return LegacyWorld.hit(hit.hitVec, face, support);
    }

    /** Traces an interaction direction without normalizing it or choosing a rotation owner. */
    public static MovingObjectPosition traceOutline(
            Minecraft client, Vec3 eye, Vec3 look, double range, LegacyRay.Fluid fluid) {
        Vec3 end = eye.add(VecMath.scale(look, range));
        return LegacyWorld.clip(
                client.theWorld,
                new LegacyRay(eye, end, LegacyRay.Block.OUTLINE, fluid, client.thePlayer));
    }

    public static MovingObjectPosition traceOutline(
            Minecraft client, Rotation rotation, double range, LegacyRay.Fluid fluid) {
        return traceOutline(
                client,
                client.thePlayer.getPositionEyes(1.0F),
                VecMath.directionFromRotation(rotation.pitch(), rotation.yaw()),
                range,
                fluid);
    }

    public static boolean solidWithoutMenu(Minecraft client, BlockPos pos) {
        IBlockState state = client.theWorld.getBlockState(pos);
        return !LegacyWorld.collision(state, client.theWorld, pos).isEmpty()
                && !state.getBlock().hasTileEntity();
    }

    public static boolean matchesBlock(MovingObjectPosition hit, BlockPos block) {
        return hit != null
                && hit.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK
                && hit.getBlockPos().equals(block);
    }

    public static boolean matchesFace(MovingObjectPosition hit, BlockPos block, EnumFacing face) {
        return matchesBlock(hit, block) && hit.sideHit == face;
    }

    /** Does not inspect the planned hit after a miss or a different support block. */
    public static boolean matchesFace(MovingObjectPosition hit, MovingObjectPosition planned) {
        return hit != null
                && hit.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK
                && hit.getBlockPos().equals(planned.getBlockPos())
                && hit.sideHit == planned.sideHit;
    }

    /** Point distance, not distance to a block's nearest box surface. */
    public static boolean withinReach(Vec3 eye, Vec3 point, double range) {
        return eye.squareDistanceTo(point) <= range * range;
    }

    /** Shared shape condition; item exclusions and material preference stay with the feature. */
    public static boolean hasSolidPlacementShape(IBlockAccess level, IBlockState state) {
        return !LegacyWorld.replaceable(state)
                && !LegacyWorld.collision(state, level, BlockPos.ORIGIN).isEmpty();
    }

    public static Vec3 facePoint(
            Minecraft client, BlockPos support, EnumFacing face, double u, double v) {
        IBlockState state = client.theWorld.getBlockState(support);
        AxisAlignedBB bounds;
        try {
            bounds = LegacyWorld.outline(state, client.theWorld, support).bounds();
        } catch (RuntimeException ignored) {
            bounds = LegacyWorld.box(0.0D, 0.0D, 0.0D, 1.0D, 1.0D, 1.0D);
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

    public static MovingObjectPosition traceFace(
            Minecraft client,
            Vec3 eye,
            float yaw,
            float pitch,
            double range,
            BlockPos support,
            EnumFacing face) {
        Vec3 end = eye.add(VecMath.scale(VecMath.directionFromRotation(pitch, yaw), range));
        MovingObjectPosition hit =
                LegacyWorld.clip(
                        client.theWorld,
                        new LegacyRay(
                                eye,
                                end,
                                LegacyRay.Block.OUTLINE,
                                LegacyRay.Fluid.NONE,
                                client.thePlayer));
        return hit != null
                        && hit.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK
                        && hit.getBlockPos().equals(support)
                        && hit.sideHit == face
                ? hit
                : null;
    }
}
