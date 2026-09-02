package com.blanoir.moons.client.event.lifecycle;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;

/**
 * Observed client-context transition at tick start. This is deliberately not
 * named WorldLoad: the underlying level change may have happened earlier.
 */
public record ClientContextChangedEvent(
        Minecraft client,
        Snapshot previous,
        Snapshot current
) {
    public record Snapshot(
            ClientLevel level,
            LocalPlayer player,
            ClientPacketListener connection
    ) { }
}
