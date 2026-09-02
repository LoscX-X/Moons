package com.blanoir.moons.client.event.world;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/** Client-world block mutation boundaries. These events are observational. */
public final class BlockUpdateEvent {
    private BlockUpdateEvent() { }

    public record Pre(
            ClientLevel level,
            BlockPos position,
            BlockState previousState,
            BlockState requestedState
    ) { }

    public record Post(
            ClientLevel level,
            BlockPos position,
            BlockState previousState,
            BlockState requestedState,
            boolean applied
    ) { }
}
