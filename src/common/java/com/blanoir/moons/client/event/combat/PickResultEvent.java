package com.blanoir.moons.client.event.combat;

import net.minecraft.client.Minecraft;
import net.minecraft.util.MovingObjectPosition;

/** Mutable result after Minecraft.pick and the built-in Reach adjustment. */
public final class PickResultEvent {
    private final Minecraft client;
    private MovingObjectPosition result;

    public PickResultEvent(Minecraft client, MovingObjectPosition result) {
        this.client = client;
        this.result = result;
    }

    public Minecraft client() {
        return client;
    }

    public MovingObjectPosition result() {
        return result;
    }

    public void result(MovingObjectPosition replacement) {
        if (replacement != null) result = replacement;
    }
}
