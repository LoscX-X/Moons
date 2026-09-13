package com.blanoir.moons.client.module.impl.player.invmanager;

import com.blanoir.moons.api.Subscription;
import com.blanoir.moons.client.access.MinecraftClientAccess;
import com.blanoir.moons.client.event.EventBus;
import com.blanoir.moons.client.event.EventPriority;
import com.blanoir.moons.client.ui.clickgui.MoonsComposeScreen;
import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.Minecraft;

/** Page navigation only. The earlier once-key listener gets first refusal on Escape. */
public final class InventoryEditorInput {
    private record Page(Object screen, Runnable back) {}

    private static Page page;

    private InventoryEditorInput() {}

    public static void init() {
        EventBus.CLIENT_CONTEXT_CHANGED.register("InvManager.editor.context", event -> page = null);
        EventBus.KEY_INPUT.register(
                "InvManager.editor.back",
                EventPriority.HIGH,
                event -> {
                    Page current = page;
                    if (current == null) return;
                    if (current.screen() != MinecraftClientAccess.screen(Minecraft.getInstance())) {
                        page = null;
                        return;
                    }
                    if (event.action() == InputConstants.PRESS
                            && event.key().key() == InputConstants.KEY_ESCAPE) {
                        page = null;
                        current.back().run();
                        event.cancel();
                    }
                });
    }

    public static Subscription attach(Runnable back) {
        Object screen = MinecraftClientAccess.screen(Minecraft.getInstance());
        if (!(screen instanceof MoonsComposeScreen)) return () -> {};
        Page current = new Page(screen, back);
        page = current;
        return () -> {
            if (page == current) page = null;
        };
    }
}
