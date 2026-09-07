package com.blanoir.moons.client.event.movement;

import com.blanoir.moons.client.event.Cancellable;

import net.minecraft.client.Minecraft;

/** Local-player tick head before the existing player update logic runs. */
public final class PlayerUpdateEvent implements Cancellable {
    private final Minecraft client;
    private boolean cancelled;

    public PlayerUpdateEvent(Minecraft client) {
        this.client = client;
    }

    public Minecraft client() {
        return client;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void cancel() {
        cancelled = true;
    }
}
