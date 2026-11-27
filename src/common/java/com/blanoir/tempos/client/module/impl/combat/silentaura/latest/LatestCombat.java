package com.blanoir.tempos.client.module.impl.combat.silentaura.latest;

import com.blanoir.tempos.client.manager.input.CombatInputController;
import com.blanoir.tempos.client.module.impl.combat.HitSelect;
import com.blanoir.tempos.client.module.impl.combat.critical.Critical;
import com.blanoir.tempos.client.module.impl.combat.silentaura.SilentAuraAttackRay;
import com.blanoir.tempos.client.module.impl.combat.silentaura.SilentAuraConfig;
import com.blanoir.tempos.client.module.impl.combat.silentaura.SilentAuraRuntime;
import com.blanoir.tempos.client.render.animation.Animations;
import com.blanoir.tempos.client.utils.math.RandomRange;
import com.blanoir.tempos.client.utils.prediction.CooldownPrediction;

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
        nextCharge = RandomRange.between(SilentAuraConfig.minCharge(), SilentAuraConfig.maxCharge());
        Critical.INSTANCE.cancelAutomaticPrediction(client);
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
            if (!"aim".equals(ray.gate())) Critical.INSTANCE.cancelAutomaticPrediction(client);
            return ray.gate();
        }
        boolean critical = SilentAuraConfig.criticalIntegration();
        if (HitSelect.INSTANCE.shouldDelay(client, ray.target())) {
            if (critical) Critical.INSTANCE.cancelAutomaticPrediction(client);
            return "hitselect " + HitSelect.INSTANCE.statusTag();
        }
        // TriggerBot leaves an already queued Critical attack in charge of dispatch.
        if (critical && Critical.INSTANCE.isAimingWindowActive()) return "critical aiming";
        boolean criticalAttack = false;
        if (critical) {
            int earliest =
                    CooldownPrediction.ticksUntilThreshold(
                            charge, nextCharge, client.player.getCurrentItemAttackStrengthDelay());
            var gate = Critical.INSTANCE.gateAutomaticAttack(client, ray.target(), earliest);
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
                        : Critical.INSTANCE.withoutSilentAuraCritical(
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
