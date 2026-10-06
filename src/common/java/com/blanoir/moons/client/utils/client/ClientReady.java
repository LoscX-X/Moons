package com.blanoir.moons.client.utils.client;

import com.blanoir.moons.client.access.MinecraftClientAccess;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;

/** Shared client-state guards; module-specific rules remain in each module. */
public final class ClientReady {
    private ClientReady() {}

    public static boolean world(Minecraft client) {
        return client != null && client.thePlayer != null && client.theWorld != null;
    }

    public static boolean interaction(Minecraft client) {
        return world(client) && client.playerController != null;
    }

    public static boolean gameplay(Minecraft client) {
        return interaction(client) && MinecraftClientAccess.screen(client) == null;
    }

    public static boolean aliveGameplay(Minecraft client) {
        return gameplay(client) && client.thePlayer.isEntityAlive();
    }

    /** Keeps a caller's captured player for both readiness and alive checks. */
    public static boolean aliveGameplay(Minecraft client, EntityPlayerSP player) {
        return client != null
                && player != null
                && client.theWorld != null
                && client.playerController != null
                && MinecraftClientAccess.screen(client) == null
                && player.isEntityAlive();
    }
}
