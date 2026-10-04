package com.blanoir.moons.client.module.impl.network.backtrack;

import com.blanoir.moons.client.compat.math.VecMath;
import com.blanoir.moons.client.management.targeting.Targeting;
import com.blanoir.moons.client.utils.world.LegacyWorld;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.monster.IMob;
import net.minecraft.entity.passive.EntityAnimal;
import net.minecraft.entity.passive.EntityWaterMob;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.Vec3;

/** Original Range nearest-target and Intent crosshair selection, shared across versions. */
final class BacktrackTargets {
    private BacktrackTargets() {}

    static boolean eligible(Minecraft client, Entity entity) {
        if (entity == null
                || client.thePlayer == null
                || entity == client.thePlayer
                || entity.riddenByEntity == client.thePlayer
                || !(entity instanceof EntityLivingBase living)
                || !living.isEntityAlive()) return false;
        if (living instanceof EntityPlayer player)
            return !player.isPlayerSleeping() && Targeting.isEnemyPlayer(client, player);
        return living instanceof EntityWaterMob
                || living instanceof IMob
                || living instanceof EntityAnimal;
    }

    static EntityLivingBase find(Minecraft client, BacktrackConfig config) {
        Vec3 eye = client.thePlayer.getPositionEyes(1.0F);
        Vec3 end = eye.add(VecMath.scale(client.thePlayer.getLook(1), config.maxRange()));
        EntityLivingBase best = null;
        double nearest = Double.POSITIVE_INFINITY;
        for (Entity entity : client.theWorld.loadedEntityList) {
            if (!eligible(client, entity)) continue;
            var box =
                    LegacyWorld.inflate(
                            entity.getEntityBoundingBox(), entity.getCollisionBorderSize());
            double distance = LegacyWorld.distanceSquared(box, eye);
            if (distance < config.minRange() * config.minRange()
                    || distance > config.maxRange() * config.maxRange()) continue;
            if (config.targetMode() == BacktrackConfig.TargetMode.INTENT) {
                var aimBox = LegacyWorld.inflate(box, .15);
                Vec3 hit =
                        aimBox.isVecInside(eye)
                                ? eye
                                : LegacyWorld.intercept(aimBox, eye, end).orElse(null);
                if (hit == null) continue;
                distance = hit.squareDistanceTo(eye);
            }
            if (distance < nearest) {
                nearest = distance;
                best = (EntityLivingBase) entity;
            }
        }
        return best;
    }

    static boolean intended(Minecraft client, EntityLivingBase target, double range) {
        Vec3 eye = client.thePlayer.getPositionEyes(1.0F);
        var box =
                LegacyWorld.inflate(
                        target.getEntityBoundingBox(), target.getCollisionBorderSize() + .15);
        return box.isVecInside(eye)
                || LegacyWorld.intercept(
                                box,
                                eye,
                                eye.add(VecMath.scale(client.thePlayer.getLook(1), range)))
                        .isPresent();
    }
}
