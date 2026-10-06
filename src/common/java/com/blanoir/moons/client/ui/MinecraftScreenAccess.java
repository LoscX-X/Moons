package com.blanoir.moons.client.ui;

import com.blanoir.moons.client.access.MinecraftClientAccess;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;

/** Shared screen ownership facade backed by the selected Minecraft version. */
public final class MinecraftScreenAccess {
    private MinecraftScreenAccess() {}

    public static GuiScreen current(Minecraft client) {
        return MinecraftClientAccess.screen(client);
    }

    public static void set(Minecraft client, GuiScreen screen) {
        MinecraftClientAccess.setScreen(client, screen);
    }
}
