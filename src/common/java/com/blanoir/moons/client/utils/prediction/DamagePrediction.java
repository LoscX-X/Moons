package com.blanoir.moons.client.utils.prediction;

import com.blanoir.moons.client.compat.math.VecMath;
import com.blanoir.moons.client.utils.combat.damage.LegacyDamage;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityEnderCrystal;
import net.minecraft.entity.item.EntityMinecartTNT;
import net.minecraft.entity.item.EntityTNTPrimed;
import net.minecraft.entity.monster.EntityCreeper;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.potion.Potion;
import net.minecraft.util.BlockPos;
import net.minecraft.util.DamageSource;
import net.minecraft.util.Vec3;
import net.minecraft.world.EnumDifficulty;
import net.minecraft.world.Explosion;

/** Estimates using 1.8.9's explosion coefficient, integer fall damage and linear armor. */
public final class DamagePrediction {
    private DamagePrediction() {}

    public static int explosionTicks(Entity entity) {
        if (entity == null || entity.isDead) return Integer.MAX_VALUE;
        if (entity instanceof EntityEnderCrystal) return 0;
        if (entity instanceof EntityTNTPrimed tnt) return Math.max(0, tnt.fuse);
        if (entity instanceof EntityMinecartTNT cart) {
            if (cart.isCollidedHorizontally
                    && VecMath.horizontalDistanceSqr(VecMath.motion(cart)) >= .01) return 0;
            return cart.isIgnited() ? Math.max(0, cart.getFuseTicks()) : Integer.MAX_VALUE;
        }
        if (entity instanceof EntityCreeper creeper) {
            if (!creeper.isEntityAlive() || !creeper.hasIgnited() && creeper.getCreeperState() <= 0)
                return Integer.MAX_VALUE;
            return Math.max(0, (int) Math.ceil(30 - creeper.getCreeperFlashIntensity(1) * 28));
        }
        return Integer.MAX_VALUE;
    }

    public static float explosionDamageFromEntity(Minecraft client, Entity entity) {
        if (client == null
                || client.theWorld == null
                || explosionTicks(entity) == Integer.MAX_VALUE) return 0;
        float power;
        Vec3 origin = VecMath.position(entity);
        if (entity instanceof EntityEnderCrystal) power = 6;
        else if (entity instanceof EntityTNTPrimed) {
            power = 4;
            origin = origin.addVector(0, .0625, 0);
        } else if (entity instanceof EntityMinecartTNT)
            power =
                    4
                            + (float)
                                            Math.min(
                                                    5,
                                                    VecMath.horizontalDistance(
                                                            VecMath.motion(entity)))
                                    * 1.5F;
        else if (entity instanceof EntityCreeper creeper) power = creeper.getPowered() ? 6 : 3;
        else return 0;
        DamageSource source =
                DamageSource.setExplosionSource(
                        new Explosion(
                                client.theWorld,
                                entity,
                                origin.xCoord,
                                origin.yCoord,
                                origin.zCoord,
                                power,
                                false,
                                false));
        return explosionDamage(client, origin, power, power * 2, power * power * 4, source);
    }

    public static float explosionDamage(
            Minecraft client,
            Vec3 pos,
            float power,
            float explosionRange,
            float damageDistance,
            DamageSource source) {
        EntityPlayer player = client == null ? null : client.thePlayer;
        if (player == null || explosionRange <= 0) return 0;
        double distance = player.getDistanceSq(pos.xCoord, pos.yCoord, pos.zCoord);
        if (distance > damageDistance) return 0;
        double exposure = client.theWorld.getBlockDensity(pos, player.getEntityBoundingBox());
        double impact = exposure * (1 - Math.sqrt(distance) / explosionRange);
        // Explosion.doExplosionA in 1.8.9 uses 8 and truncates before hurt processing.
        float raw = (int) ((impact * impact + impact) / 2 * 8 * explosionRange + 1);
        return effectiveDamage(player, source, raw);
    }

    public static float fallDamageMultiplier(Minecraft client, BlockPos pos) {
        if (pos == null || client == null || client.theWorld == null) return 1;
        var block = client.theWorld.getBlockState(pos).getBlock();
        if (block == Blocks.water || block == Blocks.flowing_water || block == Blocks.web) return 0;
        if (block == Blocks.slime_block
                && client.thePlayer != null
                && !client.thePlayer.isSneaking()) return 0;
        // Hay and beds do not reduce fall damage in 1.8.9.
        return 1;
    }

    public static float effectiveDamage(EntityPlayer player, DamageSource source, float damage) {
        return LegacyDamage.mitigate(player, source, damage);
    }

    static float scaleForDifficulty(float damage, EnumDifficulty difficulty) {
        return switch (difficulty) {
            case PEACEFUL -> 0;
            case EASY -> Math.min(damage / 2 + 1, damage);
            case NORMAL -> damage;
            case HARD -> damage * 1.5F;
        };
    }

    static int protection(EntityPlayer player, DamageSource source) {
        return Math.round(LegacyDamage.protection(player, source));
    }

    public static float fallDamage(Minecraft client, EntityPlayer player) {
        return fallDamage(
                player,
                player.fallDistance,
                fallDamageMultiplier(client, LandingPrediction.landingBlock(player)));
    }

    public static float fallDamage(EntityPlayer player, double distance, float multiplier) {
        return effectiveDamage(
                player, DamageSource.fall, rawFallDamage(player, distance, multiplier));
    }

    static int rawFallDamage(EntityPlayer player, double distance, float multiplier) {
        var jump = player.getActivePotionEffect(Potion.jump);
        return Math.max(
                0,
                (int)
                        Math.ceil(
                                (distance - 3 - (jump == null ? 0 : jump.getAmplifier() + 1))
                                        * multiplier));
    }

    public static double meleeDamage(
            Minecraft client, EntityLivingBase target, double ignoredCharge, boolean critical) {
        return client == null || client.thePlayer == null || target == null
                ? 0
                : LegacyDamage.melee(client.thePlayer, target, critical);
    }
}
