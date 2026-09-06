package com.blanoir.moons.client.module.impl.movement;

import com.blanoir.moons.client.config.settings.BooleanSetting;
import com.blanoir.moons.client.config.settings.ModeSetting;
import com.blanoir.moons.client.chat.ClientChat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.effect.MobEffects;

import java.util.List;

/**
 * Auto-sprint ported from LiquidBounce's Sprint module.
 *
 * The sprint decision is forced inside {@code LocalPlayer#aiStep} (see
 * LocalPlayer sprint-decision hooks), so sprint re-engages automatically after a screen
 * closes instead of relying on a sticky sprint-key state that vanilla resets
 * when a GUI (e.g. the inventory) opens.
 */
public final class Sprint {
    private static final BooleanSetting ENABLED =
            new BooleanSetting.Builder()
                    .name("sprint.enabled")
                    .defaultValue(false)
                    .build();

    private static final ModeSetting<Mode> MODE =
            new ModeSetting.Builder<Mode>()
                    .name("sprint.mode")
                    .defaultValue(Mode.LEGIT)
                    .option(Mode.LEGIT, "legit")
                    .option(Mode.ALWAYS, "always")
                    .option(Mode.ON_GROUND, "onground")
                    .build();

    private Sprint() {
    }

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

        return switch (MODE.get()) {
            case LEGIT -> player.input.hasForwardImpulse();
            case ALWAYS -> isMoving(player);
            case ON_GROUND -> player.onGround() && isMoving(player);
        };
    }

    private static boolean isMoving(LocalPlayer player) {
        var move = player.input.getMoveVector();
        return move.x * move.x + move.y * move.y > 1.0E-6D;
    }

    public static int setEnabled(Minecraft client, boolean newEnabled) {
        ENABLED.set(newEnabled);
        ClientChat.send(client, "Sprint " + statusText() + ". Mode: " + MODE.serialized() + ".");
        return 1;
    }

    public static int setMode(Minecraft client, String value) {
        MODE.deserialize(value);
        ClientChat.send(client, "Sprint mode set to " + MODE.serialized() + ".");
        return 1;
    }

    public static int showStatus(Minecraft client) {
        ClientChat.send(
                client,
                "Sprint: " + statusText() + ", mode: " + MODE.serialized()
                        + ". Usage: .moons sprint <enable|disable|mode legit|always|onground>"
        );
        return 1;
    }

    public static String statusText() {
        return ENABLED.get() ? "enabled" : "disabled";
    }

    /** Normalized mode value for UI and config consumers. */
    public static String modeName() {
        return MODE.serialized();
    }

    public static List<String> modeOptions() { return MODE.optionIds(); }

    private enum Mode {
        LEGIT,
        ALWAYS,
        ON_GROUND
    }
}
