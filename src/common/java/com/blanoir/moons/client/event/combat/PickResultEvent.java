package com.blanoir.moons.client.event.combat;

import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.HitResult;

/** Mutable result after Minecraft.pick and the built-in Reach adjustment. */
public final class PickResultEvent {
    private final Minecraft client;
    private HitResult result;

    public PickResultEvent(Minecraft client, HitResult result) {
        this.client = client;
        this.result = result;
    }

    public Minecraft client() {
        return client;
    }

    public HitResult result() {
        return result;
    }

    public void result(HitResult replacement) {
        if (replacement != null) result = replacement;
    }
}
