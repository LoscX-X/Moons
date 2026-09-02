package com.blanoir.moons.client.module.impl.render;

import com.blanoir.moons.client.config.settings.BooleanSetting;
import com.blanoir.moons.client.chat.ClientChat;
import net.minecraft.client.Minecraft;

public final class Clip {
    private static final BooleanSetting ENABLED =
            new BooleanSetting.Builder()
                    .name("clip.enabled")
                    .defaultValue(false)
                    .build();
    private static final int VISIBLE_RADIUS_CHUNKS = 12;

    private Clip() {
    }

    public static void init() {
    }

    public static boolean isEnabled() {
        return ENABLED.get();
    }

    public static int visibleRadiusChunks() {
        return VISIBLE_RADIUS_CHUNKS;
    }

    public static int setEnabled(Minecraft client, boolean newEnabled) {
        ENABLED.set(newEnabled);
        ClientChat.send(client, "Clip " + statusText() + ".");
        return 1;
    }

    public static String statusText() {
        return ENABLED.get() ? "enabled" : "disabled";
    }
}
