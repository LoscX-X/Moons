package com.blanoir.moons.client.module.impl.movement;

import com.blanoir.moons.client.config.settings.BooleanSetting;
import com.blanoir.moons.client.chat.ClientChat;
import net.minecraft.client.Minecraft;

public final class NoJumpDelay {
    private static final BooleanSetting ENABLED =
            new BooleanSetting.Builder()
                    .name("nojumpdelay.enabled")
                    .defaultValue(false)
                    .build();

    private NoJumpDelay() {
    }

    public static boolean isEnabled() {
        return ENABLED.get();
    }

    public static int setEnabled(Minecraft client, boolean newEnabled) {
        ENABLED.set(newEnabled);
        ClientChat.send(client, "NoJumpDelay " + statusText() + ". Usage: .moons nojumpdelay <enable|disable>");
        return 1;
    }

    public static String statusText() {
        return ENABLED.get() ? "enabled" : "disabled";
    }
}
