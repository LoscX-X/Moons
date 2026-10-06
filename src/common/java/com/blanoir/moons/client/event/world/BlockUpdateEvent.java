package com.blanoir.moons.client.event.world;

import net.minecraft.block.state.IBlockState;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.util.BlockPos;

/** Client-world block mutation boundaries. These events are observational. */
public final class BlockUpdateEvent {
    private BlockUpdateEvent() {}

    public record Pre(
            WorldClient level,
            BlockPos position,
            IBlockState previousState,
            IBlockState requestedState) {}

    public record Post(
            WorldClient level,
            BlockPos position,
            IBlockState previousState,
            IBlockState requestedState,
            boolean applied) {}
}
