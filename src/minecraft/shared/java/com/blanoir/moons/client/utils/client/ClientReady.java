package com.blanoir.moons.client.utils.client;

import com.blanoir.moons.client.access.MinecraftClientAccess;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/** Shared client-state guards; module-specific rules remain in each module. */
public final class ClientReady {
    private ClientReady() {}

    public static boolean world(Minecraft client) {
        return client != null && client.player != null && client.level != null;
    }

    public static boolean interaction(Minecraft client) {
        return world(client) && client.gameMode != null;
    }

    public static boolean gameplay(Minecraft client) {
        return interaction(client) && MinecraftClientAccess.screen(client) == null;
    }

    public static boolean aliveGameplay(Minecraft client) {
        return gameplay(client) && !client.player.isDeadOrDying();
    }

    /** Keeps a caller's captured player for both readiness and alive checks. */
    public static boolean aliveGameplay(Minecraft client, LocalPlayer player) {
        return client != null
                && player != null
                && client.level != null
                && client.gameMode != null
                && MinecraftClientAccess.screen(client) == null
                && !player.isDeadOrDying();
    }
}
