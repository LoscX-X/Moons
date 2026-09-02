package com.blanoir.moons.client.ui;

import com.blanoir.moons.client.access.MinecraftClientAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/** Minecraft 26.1 screen ownership adapter. */
public final class MinecraftScreenAccess {
    private MinecraftScreenAccess() { }

    public static Screen current(Minecraft client) {
        return MinecraftClientAccess.screen(client);
    }

    public static void set(Minecraft client, Screen screen) {
        MinecraftClientAccess.setScreen(client, screen);
    }

}
