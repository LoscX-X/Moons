package com.blanoir.moons.client.module.impl.combat.silentaura;

import com.blanoir.moons.client.event.EventBus;
import com.blanoir.moons.client.management.input.CombatInputController;
import com.blanoir.moons.client.module.impl.combat.silentaura.legacy.LegacyCombat;
import com.blanoir.moons.client.utils.client.ClientReady;

import net.minecraft.client.Minecraft;

/** Input ownership and routing at the original pre-movement TriggerBot entry. */
public final class SilentAuraCombat {
    private static boolean owned;
    private static int tickId;
    private static String gate = "idle";

    private SilentAuraCombat() {}

    public static void init() {
        EventBus.TICK.register("SilentAuraCombat.input", event -> prepare(event.client()));
        // Keep attack dispatch at the existing pre-movement input entry.
        EventBus.PLAYER_UPDATE.register(
                "SilentAuraCombat.attack",
                event -> {
                    Minecraft client = event.client();
                    if (event.isCancelled()
                            || !owned
                            || !ClientReady.aliveGameplay(client)
                            || !SilentAuraRuntime.activationHeld(client)) return;
                    gate = LegacyCombat.tick(client);
                });
    }

    private static void prepare(Minecraft client) {
        tickId++;
        if (!SilentAuraRuntime.activationHeld(client)) {
            stop(client);
            return;
        }
        if (!owned) {
            owned = true;
            LegacyCombat.reset();
        }
        while (client.gameSettings.keyBindAttack.isPressed()) {
            /* This mode owns dispatch. */
        }
        CombatInputController.suppressAttack(client, CombatInputController.Owner.SILENT_AURA);
    }

    public static void stop(Minecraft client) {
        LegacyCombat.reset();
        if (!owned) return;
        owned = false;
        SilentAuraBlock.reset(client);
        CombatInputController.releaseAttack(client, CombatInputController.Owner.SILENT_AURA);
        gate = "idle";
    }

    public static int tickId() {
        return tickId;
    }

    public static String gate() {
        return gate;
    }
}
