package com.blanoir.moons.client.module.impl.network.backtrack;

import com.blanoir.moons.client.management.targeting.Targeting;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.animal.fish.WaterAnimal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/** Original Range nearest-target and Intent crosshair selection, shared across versions. */
final class BacktrackTargets {
    private BacktrackTargets() {}

    static boolean eligible(Minecraft client, Entity entity) {
        if (entity == null
                || client.player == null
                || entity == client.player
                || entity.hasPassenger(client.player)
                || !(entity instanceof LivingEntity living)
                || !living.isAlive()) return false;
        if (living instanceof Player player)
            return !player.isSleeping() && Targeting.isEnemyPlayer(client, player);
        return living instanceof WaterAnimal
                || living instanceof Enemy
                || living instanceof NeutralMob;
    }

    static LivingEntity find(Minecraft client, BacktrackConfig config) {
        Vec3 eye = client.player.getEyePosition();
        Vec3 end = eye.add(client.player.getViewVector(1).scale(config.maxRange()));
        LivingEntity best = null;
        double nearest = Double.POSITIVE_INFINITY;
        for (Entity entity : client.level.entitiesForRendering()) {
            if (!eligible(client, entity)) continue;
            var box = entity.getBoundingBox().inflate(entity.getPickRadius());
            double distance = box.distanceToSqr(eye);
            if (distance < config.minRange() * config.minRange()
                    || distance > config.maxRange() * config.maxRange()) continue;
            if (config.targetMode() == BacktrackConfig.TargetMode.INTENT) {
                var aimBox = box.inflate(.15);
                Vec3 hit = aimBox.contains(eye) ? eye : aimBox.clip(eye, end).orElse(null);
                if (hit == null) continue;
                distance = hit.distanceToSqr(eye);
            }
            if (distance < nearest) {
                nearest = distance;
                best = (LivingEntity) entity;
            }
        }
        return best;
    }
}
