package com.blanoir.tempos.client.ui.host;

import com.blanoir.tempos.client.access.MinecraftClientAccess;
import com.blanoir.tempos.client.api.ClientUiScreen;
import com.blanoir.tempos.client.ui.clickgui.CategoryScreen;
import com.blanoir.tempos.client.ui.pages.AppearanceScreen;
import com.blanoir.tempos.client.ui.hud.HudEditorScreen;
import net.minecraft.client.Minecraft;

/** Opens the native client screens independently of retired profile layout preferences. */
public final class UiHost {
    public enum Page { CLICKGUI, HUD_EDITOR, APPEARANCE }
    private UiHost() {}

    public static void open(Page page) { open(Minecraft.getInstance(), page); }

    public static void open(Minecraft client, Page page) {
        if (client.player == null || client.level == null) return;
        var current = MinecraftClientAccess.screen(client);
        if (page == Page.HUD_EDITOR) {
            if (!(current instanceof HudEditorScreen))
                MinecraftClientAccess.setScreen(client, HudEditorScreen.create(client, current));
        } else if (page == Page.APPEARANCE) {
            if (current instanceof CategoryScreen categories) categories.appearance();
            else if (!(current instanceof AppearanceScreen))
                MinecraftClientAccess.setScreen(client, AppearanceScreen.create(client, current));
        } else if (!(current instanceof CategoryScreen)) {
            MinecraftClientAccess.setScreen(client, CategoryScreen.create(client));
        }
    }

    public static boolean isOpen(Minecraft client) {
        return MinecraftClientAccess.screen(client) instanceof ClientUiScreen;
    }

    public static void close() { close(Minecraft.getInstance()); }

    public static void close(Minecraft client) {
        if (isOpen(client)) MinecraftClientAccess.setScreen(client, null);
    }
}
