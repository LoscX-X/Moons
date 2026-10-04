package com.blanoir.moons.client.module.impl.combat.silentaura;

import com.blanoir.moons.client.management.targeting.Targeting;
import com.blanoir.moons.client.utils.combat.CombatGeometry;
import com.blanoir.moons.client.utils.combat.CombatReach;
import com.blanoir.moons.client.utils.raytrace.RaytraceUtils;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.MovingObjectPosition;

import java.util.Locale;

/** Stateless attack geometry for the Legacy CPS scheduler. */
public final class SilentAuraAttackRay {
    public record Result(EntityLivingBase target, String gate, MovingObjectPosition hit) {
        public Result(EntityLivingBase target, String gate) {
            this(target, gate, null);
        }
    }

    private SilentAuraAttackRay() {}

    public static Result find(Minecraft client) {
        EntityLivingBase intended = SilentAuraRuntime.currentTarget(client);
        if (!configured(client, intended)) return new Result(null, "no target");
        var rotation = SilentAuraRuntime.attackRotation(client);
        if (!rotation.valid() || rotation.targetId() != intended.getEntityId())
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
                                entity instanceof EntityLivingBase living
                                        && living != client.thePlayer
                                        && living.isEntityAlive()
                                        && living.canAttackWithItem()
                                        && !(living
                                                        instanceof
                                                        net.minecraft.entity.player.EntityPlayer
                                                                spectator
                                                && spectator.isSpectator()),
                        SilentAuraConfig.throughBlocks());
        EntityLivingBase target =
                intercepted instanceof EntityLivingBase living ? living : intended;
        if (!configured(client, target)) return new Result(null, "ray blocked");
        if (client.theWorld.getEntityByID(target.getEntityId()) != target)
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
