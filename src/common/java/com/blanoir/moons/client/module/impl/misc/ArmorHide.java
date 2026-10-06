package com.blanoir.moons.client.module.impl.misc;

import com.blanoir.moons.client.chat.ClientChat;
import com.blanoir.moons.client.config.settings.BooleanSetting;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;

/** Hides the local player's worn armor, head items and wings without changing equipment state. */
public final class ArmorHide {
    private static final BooleanSetting ENABLED =
            new BooleanSetting.Builder().name("armorhide.enabled").defaultValue(false).build();
    private static final ThreadLocal<EntityPlayer> CURRENT_AVATAR = new ThreadLocal<>();

    private ArmorHide() {}

    public static boolean isEnabled() {
        return ENABLED.get();
    }

    public static void beginAvatar(EntityPlayer state) {
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

        Minecraft client = Minecraft.getMinecraft();
        var currentPlayer = client.thePlayer;
        EntityPlayer state = CURRENT_AVATAR.get();
        return currentPlayer == null
                || state == null
                || state.getEntityId() != currentPlayer.getEntityId();
    }

    /** Covers head-slot items submitted directly by replacement player renderers. */
    public static boolean shouldRenderHeadItem(EntityLivingBase wearer) {
        return !ENABLED.get() || wearer != Minecraft.getMinecraft().thePlayer;
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
