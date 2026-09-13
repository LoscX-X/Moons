package com.blanoir.moons.client.module.impl.combat.silentaura.legacy;

import com.blanoir.moons.client.management.input.CombatInputController;
import com.blanoir.moons.client.module.impl.combat.critical.Critical;
import com.blanoir.moons.client.module.impl.combat.silentaura.SilentAuraAttackRay;
import com.blanoir.moons.client.module.impl.combat.silentaura.SilentAuraConfig;
import com.blanoir.moons.client.module.impl.combat.silentaura.SilentAuraRuntime;
import com.blanoir.moons.client.module.impl.render.Animations;
import com.blanoir.moons.client.utils.combat.ClickScheduler;

import net.minecraft.client.Minecraft;

/** CPS and block phases are independent of Latest's cooldown and Critical reservations. */
public final class LegacyCombat {
    private static final ClickScheduler CLICKS = new ClickScheduler();

    private LegacyCombat() {}

    public static String tick(Minecraft client) {
        var ray = SilentAuraAttackRay.find(client);
        if (ray.target() == null) return ray.gate();
        if (!CLICKS.ready(System.nanoTime())) return "cps";
        if (!LegacyBlock.beforeAttack(client)) return "unblocking";
        if (client.player.isUsingItem()) return "using item";
        // Use the installed client's native attack -> swing path before its movement packet.
        // Selecting Legacy changes combat timing, never the wire protocol or packet order.
        boolean attacked =
                Critical.withoutSilentAuraCritical(
                        () -> CombatInputController.attackTargetNow(client, ray.target(), true));
        if (!attacked) return "attack dispatch";
        CLICKS.clicked(System.nanoTime(), SilentAuraConfig.minCps(), SilentAuraConfig.maxCps());
        LegacyBlock.afterAttack(client);
        Animations.onAttack();
        SilentAuraRuntime.onSuccessfulAttack(client, ray.target());
        return "attack";
    }

    public static void reset() {
        CLICKS.reset();
    }
}
