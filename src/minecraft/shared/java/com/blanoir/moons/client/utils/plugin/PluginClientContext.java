package com.blanoir.moons.client.utils.plugin;

import net.minecraft.client.Minecraft;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.Locale;

/** Shared client-only sampling and scope helpers for plugin appearance features. */
public final class PluginClientContext {
    private PluginClientContext() {}

    public static String scope(Minecraft client) {
        if (client == null || client.level == null) return "";
        var integrated = client.getSingleplayerServer();
        if (integrated != null) {
            return "local:"
                    + integrated.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        }
        var server = client.getCurrentServer();
        return server == null ? "" : "server:" + server.ip.trim().toLowerCase(Locale.ROOT);
    }

    public static PluginBlockSelector lookingAt(Minecraft client) {
        if (client == null
                || client.level == null
                || !(client.hitResult instanceof BlockHitResult hit)
                || hit.getType() != HitResult.Type.BLOCK) {
            throw new IllegalArgumentException("Look at a block first.");
        }
        var state = client.level.getBlockState(hit.getBlockPos());
        if (state.isAir()) throw new IllegalArgumentException("Look at a non-air block first.");
        return PluginBlockSelector.capture(state);
    }
}
