package com.blanoir.moons.client.module.impl.combat.silentaura;

import com.blanoir.moons.client.management.targeting.Targeting;
import com.blanoir.moons.client.utils.combat.CombatReach;
import com.blanoir.moons.client.utils.raytrace.RaytraceUtils;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import java.util.Locale;

/** Stateless geometry shared by the two independently scheduled combat modes. */
public final class SilentAuraAttackRay {
    public record Result(LivingEntity target, String gate) {}

    private SilentAuraAttackRay() {}

    public static Result find(Minecraft client) {
        LivingEntity intended = SilentAuraRuntime.currentTarget(client);
        if (!configured(client, intended)) return new Result(null, "no target");
        var rotation = SilentAuraRuntime.attackRotation(client);
        if (!rotation.valid() || rotation.targetId() != intended.getId())
            return new Result(null, "waiting rotation");
        double range = CombatReach.entityInteractionRange(client, SilentAuraConfig.aimRange());
        if (range <= 0) return new Result(null, "range");
        Entity intercepted =
                Targeting.findTargetOnRay(
                        client,
                        rotation.eye(),
                        rotation.look(),
                        range,
                        entity ->
                                entity instanceof LivingEntity living
                                        && living != client.player
                                        && living.isAlive()
                                        && living.isAttackable()
                                        && !living.isSpectator(),
                        SilentAuraConfig.throughBlocks());
        LivingEntity target = intercepted instanceof LivingEntity living ? living : intended;
        if (!configured(client, target)) return new Result(null, "ray blocked");
        if (client.level.getEntity(target.getId()) != target)
            return new Result(null, "target moved");
        var ray =
                RaytraceUtils.traceEntity(
                        client,
                        rotation.eye(),
                        rotation.look(),
                        range,
                        target,
                        SilentAuraConfig.throughBlocks());
        return ray == RaytraceUtils.EntityRayState.HIT
                ? new Result(target, "hit")
                : new Result(null, ray.name().toLowerCase(Locale.ROOT));
    }

    private static boolean configured(Minecraft client, Entity target) {
        return Targeting.isConfiguredTarget(
                client,
                target,
                SilentAuraConfig.targetPlayers(),
                false,
                SilentAuraConfig.targetEntityTypes());
    }
}
