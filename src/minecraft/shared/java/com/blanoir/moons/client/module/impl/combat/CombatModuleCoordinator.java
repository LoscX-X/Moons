package com.blanoir.moons.client.module.impl.combat;

import net.minecraft.client.Minecraft;

import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;

/** Central policy for combat modules that cannot own rotation at the same time. */
public final class CombatModuleCoordinator {
    public enum Role {
        AIM_ASSIST,
        TRIGGER_BOT,
        SILENT_AURA
    }

    private static boolean transitioning;
    private static BooleanSupplier aimAssistEnabled = () -> false;
    private static BiConsumer<Minecraft, Boolean> aimAssistToggle = (client, enabled) -> {};

    private CombatModuleCoordinator() {}

    public static void bindAimAssist(
            BooleanSupplier enabled, BiConsumer<Minecraft, Boolean> toggle) {
        aimAssistEnabled = enabled;
        aimAssistToggle = toggle;
    }

    public static void beforeEnable(Minecraft client, Role role) {
        if (transitioning) {
            return;
        }
        transitioning = true;
        try {
            if (role == Role.SILENT_AURA) {
                if (TriggerBot.isEnabled()) {
                    TriggerBot.setEnabled(client, false);
                }
                if (aimAssistEnabled.getAsBoolean()) {
                    aimAssistToggle.accept(client, false);
                }
                return;
            }
            if (SilentAura.isEnabled()) {
                SilentAura.setEnabled(client, false);
            }
        } finally {
            transitioning = false;
        }
    }

    public static void reconcileConfiguredState(Minecraft client) {
        if (SilentAura.isEnabled()) {
            beforeEnable(client, Role.SILENT_AURA);
        }
    }

    public static boolean isAuraActive() {
        return SilentAura.isEnabled();
    }
}
