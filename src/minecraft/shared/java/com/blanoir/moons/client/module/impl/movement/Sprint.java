package com.blanoir.moons.client.module.impl.movement;

import com.blanoir.moons.client.chat.ClientChat;
import com.blanoir.moons.client.config.settings.BooleanSetting;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.effect.MobEffects;

/**
 * Automatic sprint control. Source attribution is recorded in THIRD_PARTY_NOTICES.md.
 *
 * The sprint decision is forced inside {@code LocalPlayer#aiStep} (see
 * LocalPlayer sprint-decision hooks), so sprint re-engages automatically after a screen
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
    public static boolean shouldSprint(LocalPlayer player) {
        if (!ENABLED.get() || player == null) {
            return false;
        }

        if (player.isPassenger()
                || player.getAbilities().flying
                || player.isFallFlying()
                || player.isShiftKeyDown()
                || player.isUsingItem()
                || player.isInShallowWater()
                || player.hasEffect(MobEffects.BLINDNESS)
                || player.getFoodData().getFoodLevel() <= 6
                || (player.horizontalCollision && !player.minorHorizontalCollision)) {
            return false;
        }

        return player.input.hasForwardImpulse();
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
