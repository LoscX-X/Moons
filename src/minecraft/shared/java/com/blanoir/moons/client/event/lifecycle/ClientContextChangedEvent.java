package com.blanoir.moons.client.event.lifecycle;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.network.NetHandlerPlayClient;

/**
 * Observed client-context transition at tick start. This is deliberately not
 * named WorldLoad: the underlying level change may have happened earlier.
 */
public record ClientContextChangedEvent(Minecraft client, Snapshot previous, Snapshot current) {
    public record Snapshot(
            WorldClient level, EntityPlayerSP player, NetHandlerPlayClient connection) {}
}
