package com.blanoir.moons.client.utils.plugin;

import net.minecraft.client.Minecraft;
import net.minecraft.util.MovingObjectPosition;

import java.util.Locale;

/** Shared client-only sampling and scope helpers for plugin appearance features. */
public final class PluginClientContext {
    private PluginClientContext() {}

    public static String scope(Minecraft client) {
        if (client == null || client.theWorld == null) return "";
        var integrated = client.getIntegratedServer();
        if (integrated != null) {
            return "local:"
                    + client.mcDataDir
                            .toPath()
                            .resolve("saves")
                            .resolve(integrated.getFolderName())
                            .toAbsolutePath()
                            .normalize();
        }
        var server = client.getCurrentServerData();
        return server == null ? "" : "server:" + server.serverIP.trim().toLowerCase(Locale.ROOT);
    }

    public static PluginBlockSelector lookingAt(Minecraft client) {
        if (client == null
                || client.theWorld == null
                || client.objectMouseOver == null
                || client.objectMouseOver.typeOfHit
                        != MovingObjectPosition.MovingObjectType.BLOCK) {
            throw new IllegalArgumentException("Look at a block first.");
        }
        var state = client.theWorld.getBlockState(client.objectMouseOver.getBlockPos());
        if (state.getBlock().getMaterial() == net.minecraft.block.material.Material.air)
            throw new IllegalArgumentException("Look at a non-air block first.");
        return PluginBlockSelector.capture(state);
    }
}
