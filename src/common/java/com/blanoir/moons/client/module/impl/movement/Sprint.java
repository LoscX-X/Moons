package com.blanoir.moons.client.module.impl.movement;

import com.blanoir.moons.client.chat.ClientChat;
import com.blanoir.moons.client.config.settings.BooleanSetting;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.potion.Potion;

/**
 * Automatic sprint control.
 *
 * The sprint decision is forced inside {@code EntityPlayerSP#aiStep} (see
 * EntityPlayerSP sprint-decision hooks), so sprint re-engages automatically after a screen
 * closes instead of relying on a sticky sprint-key state that vanilla resets
 * when a GUI (e.g. the inventory) opens.
 */
public final class Sprint {
    private static final BooleanSetting ENABLED =
            new BooleanSetting.Builder().name("sprint.enabled").defaultValue(false).build();

    private Sprint() {}

    public static boolean isEnabled() {
        return ENABLED.get();
    }

    /** Whether the local player should be sprinting during this movement tick. */
    public static boolean shouldSprint(EntityPlayerSP player) {
        if (!ENABLED.get() || player == null) {
            return false;
        }

        if (player.isRiding()
                || player.capabilities.isFlying
                || player.isSneaking()
                || player.isUsingItem()
                || player.isInWater()
                || player.isPotionActive(Potion.blindness)
                || player.getFoodStats().getFoodLevel() <= 6
                || player.isCollidedHorizontally) {
            return false;
        }

        return player.movementInput.moveForward >= 0.8F;
    }

    public static int setEnabled(Minecraft client, boolean newEnabled) {
        ENABLED.set(newEnabled);
        ClientChat.send(client, "Sprint " + statusText() + ".");
        return 1;
    }

    public static int showStatus(Minecraft client) {
        ClientChat.send(
                client, "Sprint: " + statusText() + ". Usage: .moons sprint <enable|disable>");
        return 1;
    }

    public static String statusText() {
        return ENABLED.get() ? "enabled" : "disabled";
    }

    /** The only supported sprint behavior. */
    public static String modeName() {
        return "legit";
    }
}
