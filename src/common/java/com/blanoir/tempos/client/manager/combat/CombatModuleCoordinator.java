package com.blanoir.tempos.client.manager.combat;

import com.blanoir.tempos.client.module.impl.combat.SilentAura;
import com.blanoir.tempos.client.module.impl.combat.TriggerBot;
import com.blanoir.tempos.client.module.impl.combat.cushionaura.CushionAura;

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
                if (TriggerBot.INSTANCE.isEnabled()) {
                    TriggerBot.INSTANCE.setEnabled(client, false);
                }
                if (aimAssistEnabled.getAsBoolean()) {
                    aimAssistToggle.accept(client, false);
                }
                return;
            }
            if (CushionAura.INSTANCE.isEnabled()) CushionAura.INSTANCE.setEnabled(client, false);
            if (SilentAura.INSTANCE.isEnabled()) {
                SilentAura.INSTANCE.setEnabled(client, false);
            }
        } finally {
            transitioning = false;
        }
    }

    public static void reconcileConfiguredState(Minecraft client) {
        if (SilentAura.INSTANCE.isEnabled() || CushionAura.INSTANCE.isEnabled()) {
            beforeEnable(client, Role.SILENT_AURA);
        }
    }

    public static boolean isAuraActive() {
        return SilentAura.INSTANCE.isEnabled() || CushionAura.INSTANCE.isEnabled();
    }
}
