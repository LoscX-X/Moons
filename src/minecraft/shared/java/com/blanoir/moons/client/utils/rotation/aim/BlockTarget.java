package com.blanoir.moons.client.utils.rotation.aim;

import com.blanoir.moons.client.compat.math.VecMath;

import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.Vec3;

/** A support/face pair. It identifies both the clicked face and the adjacent placement cell. */
public record BlockTarget(BlockPos support, EnumFacing face) {
    public BlockPos placePos() {
        return support.offset(face);
    }

    /** Same center coordinates as placePos(), without allocating a BlockPos and Vec3 per comparison. */
    public double placeDistanceSquared(Vec3 center) {
        return VecMath.distanceToSqr(
                center,
                (support.getX() + face.getFrontOffsetX()) + .5D,
                (support.getY() + face.getFrontOffsetY()) + .5D,
                (support.getZ() + face.getFrontOffsetZ()) + .5D);
    }

    public double supportDistanceSquared(Vec3 center) {
        return VecMath.distanceToSqr(
                center, support.getX() + .5D, support.getY() + .5D, support.getZ() + .5D);
    }
}
