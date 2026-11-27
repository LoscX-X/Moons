package com.blanoir.tempos.client.module.impl.render;

import com.blanoir.tempos.client.access.MinecraftClientAccess;
import com.blanoir.tempos.client.chat.ClientChat;
import com.blanoir.tempos.client.config.settings.BooleanSetting;

import net.minecraft.client.Minecraft;

/**
 * Caver: the through-wall terrain X-ray.
 *
 * <p>On Minecraft 26.4-pre-2, normal block-model generation and face culling are retained.
 * Quads emitted by SectionCompiler are rendered at about 38% opacity.
 */
public final class Caver {
    private static final BooleanSetting ENABLED =
            new BooleanSetting.Builder().name("caver.enabled").defaultValue(true).build();

    private Caver() {}

    public static boolean isEnabled() {
        return ENABLED.get();
    }

    public static void setEnabled(Minecraft client, boolean newEnabled) {
        if (ENABLED.get() == newEnabled) {
            ClientChat.send(client, "Caver is already " + statusText() + ".");
            return;
        }

        ENABLED.set(newEnabled);
        MinecraftClientAccess.rebuildLevelRenderer(client);

        ClientChat.send(
                client,
                "Caver "
                        + statusText()
                        + ". Minecraft 26.4-pre-2 terrain quads render at about 38% opacity.");
    }

    public static void toggle(Minecraft client) {
        setEnabled(client, !ENABLED.get());
    }

    public static String statusText() {
        return ENABLED.get() ? "enabled" : "disabled";
    }
}
