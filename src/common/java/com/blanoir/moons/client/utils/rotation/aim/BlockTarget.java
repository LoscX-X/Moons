package com.blanoir.moons.client.utils.rotation.aim;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

/** A support/face pair. It identifies both the clicked face and the adjacent placement cell. */
public record BlockTarget(BlockPos support, Direction face) {
    public BlockPos placePos() {
        return support.relative(face);
    }

    /** Same center coordinates as placePos(), without allocating a BlockPos and Vec3 per comparison. */
    public double placeDistanceSquared(Vec3 center) {
        return center.distanceToSqr(
                (support.getX() + face.getStepX()) + .5D,
                (support.getY() + face.getStepY()) + .5D,
                (support.getZ() + face.getStepZ()) + .5D);
    }

    public double supportDistanceSquared(Vec3 center) {
        return center.distanceToSqr(
                support.getX() + .5D, support.getY() + .5D, support.getZ() + .5D);
    }
}
