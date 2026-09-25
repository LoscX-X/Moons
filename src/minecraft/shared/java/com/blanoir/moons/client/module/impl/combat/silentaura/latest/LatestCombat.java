package com.blanoir.moons.client.module.impl.combat.silentaura.latest;

import com.blanoir.moons.client.management.input.CombatInputController;
import com.blanoir.moons.client.module.impl.combat.HitSelect;
import com.blanoir.moons.client.module.impl.combat.critical.Critical;
import com.blanoir.moons.client.module.impl.combat.silentaura.SilentAuraAttackRay;
import com.blanoir.moons.client.module.impl.combat.silentaura.SilentAuraConfig;
import com.blanoir.moons.client.module.impl.combat.silentaura.SilentAuraRuntime;
import com.blanoir.moons.client.module.impl.render.Animations;
import com.blanoir.moons.client.utils.math.RandomMath;
import com.blanoir.moons.client.utils.prediction.CooldownPrediction;

import net.minecraft.client.Minecraft;

import java.util.Locale;

/** Modern weapon charge and Critical reservations have no Legacy attack state. */
public final class LatestCombat {
    private static double nextCharge = 1;
    private static int fullChargeTicks;
    private static int sampledTick = Integer.MIN_VALUE;
    private static double charge;

    private LatestCombat() {}

    public static void reset(Minecraft client) {
        fullChargeTicks = 0;
        sampledTick = Integer.MIN_VALUE;
        charge = 0;
        nextCharge = RandomMath.between(SilentAuraConfig.minCharge(), SilentAuraConfig.maxCharge());
        Critical.cancelAutomaticPrediction(client);
    }

    public static void prepare(Minecraft client) {
        if (sampledTick == client.player.tickCount) return;
        sampledTick = client.player.tickCount;
        float cooldown = client.player.getAttackStrengthScale(0);
        if (cooldown < 1) {
            fullChargeTicks = 0;
            charge = cooldown;
        } else {
            charge =
                    cooldown
                            + ++fullChargeTicks / client.player.getCurrentItemAttackStrengthDelay();
        }
    }

    public static String tick(Minecraft client) {
        prepare(client);
        var ray = SilentAuraAttackRay.find(client);
        if (ray.target() == null) {
            if (!"aim".equals(ray.gate())) Critical.cancelAutomaticPrediction(client);
            return ray.gate();
        }
        boolean critical = SilentAuraConfig.criticalIntegration();
        if (HitSelect.shouldDelay(client, ray.target())) {
            if (critical) Critical.cancelAutomaticPrediction(client);
            return "hitselect " + HitSelect.statusTag();
        }
        // TriggerBot leaves an already queued Critical attack in charge of dispatch.
        if (critical && Critical.isAimingWindowActive()) return "critical aiming";
        boolean criticalAttack = false;
        if (critical) {
            int earliest =
                    CooldownPrediction.ticksUntilThreshold(
                            charge, nextCharge, client.player.getCurrentItemAttackStrengthDelay());
            var gate = Critical.gateAutomaticAttack(client, ray.target(), earliest);
            if (gate != Critical.AutomaticAttackGate.ALLOW
                    && gate != Critical.AutomaticAttackGate.ATTACK)
                return "critical " + gate.name().toLowerCase(Locale.ROOT);
            criticalAttack = gate == Critical.AutomaticAttackGate.ATTACK;
        }
        if (!criticalAttack && charge + 1.0E-4 < nextCharge)
            return String.format(Locale.ROOT, "charge %.2f/%.2f", charge, nextCharge);
        boolean attacked =
                critical
                        ? CombatInputController.attackTargetNow(client, ray.hit(), true)
                        : Critical.withoutSilentAuraCritical(
                                () ->
                                        CombatInputController.attackTargetNow(
                                                client, ray.hit(), true));
        if (!attacked) return "attack dispatch";
        reset(client);
        Animations.onAttack();
        SilentAuraRuntime.onSuccessfulAttack(client, ray.target());
        return "attack";
    }
}
