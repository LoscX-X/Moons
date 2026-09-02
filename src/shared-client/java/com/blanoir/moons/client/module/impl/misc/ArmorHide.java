package com.blanoir.moons.client.module.impl.misc;

import com.blanoir.moons.client.config.settings.BooleanSetting;
import com.blanoir.moons.client.chat.ClientChat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;

/** Hides only the local player's worn armor model without changing equipment state. */
public final class ArmorHide {
    private static final BooleanSetting ENABLED = new BooleanSetting.Builder()
            .name("armorhide.enabled")
            .defaultValue(false)
            .build();
    private static final ThreadLocal<AvatarRenderState> CURRENT_AVATAR = new ThreadLocal<>();

    private ArmorHide() {
    }

    public static boolean isEnabled() {
        return ENABLED.get();
    }

    public static void beginAvatar(AvatarRenderState state) {
        if (state == null) {
            CURRENT_AVATAR.remove();
        } else {
            CURRENT_AVATAR.set(state);
        }
    }

    public static void endAvatar() {
        CURRENT_AVATAR.remove();
    }

    public static boolean shouldRenderCurrentArmor() {
        if (!ENABLED.get()) {
            return true;
        }

        Minecraft client = Minecraft.getInstance();
        AvatarRenderState state = CURRENT_AVATAR.get();
        return client.player == null || state == null || state.id != client.player.getId();
    }

    public static int setEnabled(Minecraft client, boolean enabled) {
        ENABLED.set(enabled);
        if (!enabled) {
            CURRENT_AVATAR.remove();
        }
        ClientChat.send(client, "ArmorHide " + (enabled ? "enabled" : "disabled") + ".");
        return 1;
    }
}
