package com.blanoir.moons.client.module.impl.combat.silentaura;

import com.blanoir.moons.client.management.targeting.Targeting;
import com.blanoir.moons.client.utils.combat.CombatGeometry;
import com.blanoir.moons.client.utils.combat.CombatReach;
import com.blanoir.moons.client.utils.raytrace.RaytraceUtils;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.EntityHitResult;

import java.util.Locale;

/** Stateless geometry shared by the two independently scheduled combat modes. */
public final class SilentAuraAttackRay {
    public record Result(LivingEntity target, String gate, EntityHitResult hit) {
        public Result(LivingEntity target, String gate) {
            this(target, gate, null);
        }
    }

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
                CombatGeometry.findTargetOnRay(
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
                CombatGeometry.traceEntity(
                        client,
                        rotation.eye(),
                        rotation.look(),
                        range,
                        target,
                        SilentAuraConfig.throughBlocks());
        if (ray != RaytraceUtils.EntityRayState.HIT)
            return new Result(null, ray.name().toLowerCase(Locale.ROOT));
        var hit = CombatGeometry.attackHit(client, target, rotation.eye(), rotation.look(), range);
        return hit == null ? new Result(null, "target moved") : new Result(target, "hit", hit);
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
