package com.blanoir.tempos.client.module.impl.movement;

import com.blanoir.tempos.client.chat.ClientChat;
import com.blanoir.tempos.client.config.settings.BooleanSetting;
import com.blanoir.tempos.client.module.framework.ClientFeature;
import com.blanoir.tempos.client.module.framework.ModuleCategory;
import com.blanoir.tempos.client.module.framework.ModuleInfo;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.effect.MobEffects;

/**
 * Automatic sprint control.
 *
 * The sprint decision is forced inside {@code LocalPlayer#aiStep} (see
 * LocalPlayer sprint-decision hooks), so sprint re-engages automatically after a screen
 * closes instead of relying on a sticky sprint-key state that vanilla resets
 * when a GUI (e.g. the inventory) opens.
 */
@ModuleInfo(
        category = ModuleCategory.MOVEMENT,
        description = "Starts sprinting automatically when the movement conditions are met.")
public final class Sprint extends ClientFeature {
    public static final Sprint INSTANCE = new Sprint();

    private final BooleanSetting enabled =
            new BooleanSetting.Builder().name("sprint.enabled").defaultValue(false).build();

    private Sprint() {
        super("sprint", "Sprint");
    }

    @Override
    public boolean isEnabled() {
        return this.enabled.get();
    }

    /** Whether the local player should be sprinting during this movement tick. */
    public boolean shouldSprint(LocalPlayer player) {
        return canSprint(player) && player.input.hasForwardImpulse();
    }

    /** Eligibility before directional input is chosen by a movement module. */
    public boolean canSprint(LocalPlayer player) {
        if (!this.enabled.get() || player == null) {
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

        return true;
    }

    @Override
    public int setEnabled(Minecraft client, boolean newEnabled) {
        this.enabled.set(newEnabled);
        ClientChat.send(client, "Sprint " + statusText() + ".");
        return 1;
    }

    public int showStatus(Minecraft client) {
        ClientChat.send(
                client, "Sprint: " + statusText() + ". Usage: .tempos sprint <enable|disable>");
        return 1;
    }

    public String statusText() {
        return this.enabled.get() ? "enabled" : "disabled";
    }

    /** The only supported sprint behavior. */
    public static String modeName() {
        return "legit";
    }

    @Override
    protected String statusTag() {
        return Sprint.modeName();
    }
}
