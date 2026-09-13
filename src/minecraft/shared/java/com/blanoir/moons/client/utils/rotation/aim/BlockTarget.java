package com.blanoir.moons.client.utils.rotation.aim;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/** A support/face pair. It identifies both the clicked face and the adjacent placement cell. */
public record BlockTarget(BlockPos support, Direction face) {
    public BlockPos placePos() {
        return support.relative(face);
    }
}
