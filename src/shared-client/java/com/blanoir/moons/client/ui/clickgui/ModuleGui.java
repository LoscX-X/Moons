package com.blanoir.moons.client.ui.clickgui;

import com.blanoir.moons.client.access.MinecraftClientAccess;

import net.minecraft.client.Minecraft;

/**
 * In-game entry point for the Compose/Skia module GUI.
 *
 * <p>The GUI key is handled by the standalone keyboard hook,
 * so no vanilla {@code KeyMapping} is registered here.
 */
public final class ModuleGui {
    private ModuleGui() {
    }

    public static void open(Minecraft client) {
        if (client.player == null || client.level == null) {
            return;
        }
        if (MinecraftClientAccess.screen(client) instanceof MoonsComposeScreen) {
            return;
        }
        MinecraftClientAccess.setScreen(client, new MoonsComposeScreen());
    }

    public static void toggle(Minecraft client) {
        if (MinecraftClientAccess.screen(client) instanceof MoonsComposeScreen) {
            MinecraftClientAccess.setScreen(client, null);
            return;
        }
        open(client);
    }

    public static boolean isOpen() {
        return MinecraftClientAccess.screen(Minecraft.getInstance()) instanceof MoonsComposeScreen;
    }

    public static int setEnabled(Minecraft client, boolean enabled) {
        if (enabled) {
            open(client);
        } else if (MinecraftClientAccess.screen(client) instanceof MoonsComposeScreen) {
            MinecraftClientAccess.setScreen(client, null);
        }
        return 1;
    }
}
