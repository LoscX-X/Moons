package com.blanoir.moons.client.module.impl.render;

import com.blanoir.moons.client.access.MinecraftClientAccess;

import com.blanoir.moons.client.config.settings.BooleanSetting;
import com.blanoir.moons.client.chat.ClientChat;
import net.minecraft.client.Minecraft;

/**
 * Caver: the through-wall terrain X-ray.
 *
 * <p>When enabled, fully buried solid blocks are hidden. Exposed solid terrain and
 * non-full blocks (banners, flowers, grass, leaves, ...) render at 75% transparency.
 */
public final class Caver {
    private static final BooleanSetting ENABLED =
            new BooleanSetting.Builder()
                    .name("caver.enabled")
                    .defaultValue(true)
                    .build();

    private Caver() {
    }

    public static boolean isEnabled() {
        return ENABLED.get();
    }

    public static void setEnabled(Minecraft client, boolean newEnabled) {
        var currentLevel = client == null ? null : client.level;
        if (ENABLED.get() == newEnabled) {
            ClientChat.send(client, "Caver is already " + statusText() + ".");
            return;
        }

        ENABLED.set(newEnabled);

        if (client != null && currentLevel != null && client.levelRenderer != null) {
            MinecraftClientAccess.rebuildLevelRenderer(client);
        }

        ClientChat.send(
                client,
                "Caver "
                        + statusText()
                        + ". Buried solid blocks are hidden; exposed terrain and non-full blocks are 75% transparent."
        );
    }

    public static void toggle(Minecraft client) {
        setEnabled(client, !ENABLED.get());
    }

    public static String statusText() {
        return ENABLED.get() ? "enabled" : "disabled";
    }
}
