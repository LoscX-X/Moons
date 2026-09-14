package com.blanoir.moons.client.module.impl.misc;

import com.blanoir.moons.client.chat.ClientChat;
import com.blanoir.moons.client.config.settings.BooleanSetting;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/** Hides the local player's worn armor, head items and wings without changing equipment state. */
public final class ArmorHide {
    private static final BooleanSetting ENABLED =
            new BooleanSetting.Builder().name("armorhide.enabled").defaultValue(false).build();
    private static final ThreadLocal<AvatarRenderState> CURRENT_AVATAR = new ThreadLocal<>();

    private ArmorHide() {}

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
        var currentPlayer = client.player;
        AvatarRenderState state = CURRENT_AVATAR.get();
        return currentPlayer == null || state == null || state.id != currentPlayer.getId();
    }

    /** Covers head-slot items submitted directly by replacement player renderers. */
    public static boolean shouldRenderHeadItem(
            LivingEntity wearer, ItemStack item, ItemDisplayContext context) {
        if (!ENABLED.get() || context != ItemDisplayContext.HEAD) return true;
        var player = Minecraft.getInstance().player;
        return player == null
                || wearer != player
                || !ItemStack.isSameItemSameComponents(item, player.getItemBySlot(EquipmentSlot.HEAD));
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
