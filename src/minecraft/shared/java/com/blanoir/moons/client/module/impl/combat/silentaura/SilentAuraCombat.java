package com.blanoir.moons.client.module.impl.combat.silentaura;

import com.blanoir.moons.client.event.EventBus;
import com.blanoir.moons.client.management.input.CombatInputController;
import com.blanoir.moons.client.module.impl.combat.silentaura.latest.LatestCombat;
import com.blanoir.moons.client.module.impl.combat.silentaura.legacy.LegacyCombat;
import com.blanoir.moons.client.utils.client.ClientReady;

import net.minecraft.client.Minecraft;

/** Input ownership and routing at the original pre-movement TriggerBot entry. */
public final class SilentAuraCombat {
    private static boolean owned;
    private static boolean legacy;
    private static int tickId;
    private static String gate = "idle";

    private SilentAuraCombat() {}

    public static void init() {
        EventBus.TICK.register("SilentAuraCombat.input", event -> prepare(event.client()));
        // Both combat modes run on this client's protocol. Never dispatch from MOTION_POST:
        // movement closes the interaction window, before vanilla sends CLIENT_TICK_END.
        EventBus.PLAYER_UPDATE.register(
                "SilentAuraCombat.attack",
                event -> {
                    Minecraft client = event.client();
                    if (event.isCancelled()
                            || !owned
                            || !ClientReady.aliveGameplay(client)
                            || !SilentAuraRuntime.activationHeld(client)) return;
                    gate = legacy ? LegacyCombat.tick(client) : LatestCombat.tick(client);
                });
    }

    private static void prepare(Minecraft client) {
        tickId++;
        if (!SilentAuraRuntime.activationHeld(client)) {
            stop(client);
            return;
        }
        if (owned && legacy != SilentAuraConfig.legacyCombat()) stop(client);
        if (!owned) {
            owned = true;
            legacy = SilentAuraConfig.legacyCombat();
            LegacyCombat.reset();
            LatestCombat.reset(client);
        }
        while (client.options.keyAttack.consumeClick()) {
            /* This mode owns dispatch. */
        }
        CombatInputController.suppressAttack(client, CombatInputController.Owner.SILENT_AURA);
        if (!legacy) LatestCombat.prepare(client);
    }

    public static void stop(Minecraft client) {
        LegacyCombat.reset();
        if (!owned) return;
        owned = false;
        SilentAuraBlock.reset(client);
        CombatInputController.releaseAttack(client, CombatInputController.Owner.SILENT_AURA);
        LatestCombat.reset(client);
        gate = "idle";
    }

    public static int tickId() {
        return tickId;
    }

    public static String gate() {
        return gate;
    }

    public static int attackChargePercent(Minecraft client) {
        return client == null || client.player == null
                ? 0
                : (int) Math.round(Math.clamp(client.player.getAttackStrengthScale(0), 0, 1) * 100);
    }
}
