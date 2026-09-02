package com.blanoir.moons.client.event.combat;

import com.blanoir.moons.client.event.Cancellable;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;

/** Start/continue block-breaking request before the game-mode call proceeds. */
public final class BlockBreakEvent implements Cancellable {
    private final MultiPlayerGameMode gameMode;
    private final BlockPos position;
    private boolean cancelled;

    public BlockBreakEvent(MultiPlayerGameMode gameMode, BlockPos position) {
        this.gameMode = gameMode;
        this.position = position;
    }

    public MultiPlayerGameMode gameMode() { return gameMode; }
    public BlockPos position() { return position; }
    @Override public boolean isCancelled() { return cancelled; }
    @Override public void cancel() { cancelled = true; }
}
