package com.blanoir.moons.client.utils.prediction;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.minecart.MinecartTNT;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ServerExplosion;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** Existing melee, explosion and fall damage estimates, independent of module settings. */
public final class DamagePrediction {
    private DamagePrediction() {}

    public static float explosionDamageFromEntity(Minecraft client, Entity entity) {
        if (entity instanceof EndCrystal) {
            return explosionDamage(
                    client,
                    entity.position(),
                    6.0F,
                    12.0F,
                    144.0F,
                    Explosion.getDefaultDamageSource(client.level, entity));
        }

        if (entity instanceof PrimedTnt) {
            return explosionDamage(
                    client,
                    entity.position().add(0.0D, 0.0625D, 0.0D),
                    4.0F,
                    8.0F,
                    64.0F,
                    Explosion.getDefaultDamageSource(client.level, entity));
        }

        if (entity instanceof MinecartTNT) {
            return explosionDamage(
                    client,
                    entity.position(),
                    4.0F,
                    8.0F,
                    64.0F,
                    Explosion.getDefaultDamageSource(client.level, entity));
        }

        if (entity instanceof Creeper creeper) {
            float power = (creeper.isPowered() ? 2.0F : 1.0F) * 3.0F;
            return explosionDamage(
                    client,
                    entity.position(),
                    power,
                    power * 2.0F,
                    power * power * 4.0F,
                    Explosion.getDefaultDamageSource(client.level, entity));
        }

        return 0.0F;
    }

    /**
     * Mirrors the vanilla entity damage formula for explosions.
     */
    public static float explosionDamage(
            Minecraft client,
            Vec3 pos,
            float power,
            float explosionRange,
            float damageDistance,
            DamageSource source) {
        Player player = client.player;
        if (player == null || player.distanceToSqr(pos) > damageDistance) {
            return 0.0F;
        }

        float exposure = ServerExplosion.getSeenPercent(pos, player);
        double distanceDecay = 1.0D - Math.sqrt(player.distanceToSqr(pos)) / explosionRange;
        double pre = exposure * distanceDecay;
        double damage = (pre * pre + pre) / 2.0D * 7.0D * explosionRange + 1.0D;
        if (damage == 0.0D) {
            return 0.0F;
        }

        return effectiveDamage(player, source, (float) damage);
    }

    public static float fallDamageMultiplier(Minecraft client, BlockPos pos) {
        if (pos == null) {
            return 1.0F;
        }

        Block block = client.level.getBlockState(pos).getBlock();
        if (block == Blocks.WATER || block == Blocks.COBWEB || block == Blocks.POWDER_SNOW) {
            return 0.0F;
        }
        if (block == Blocks.HAY_BLOCK || block == Blocks.HONEY_BLOCK) {
            return 0.2F;
        }
        if (block == Blocks.SLIME_BLOCK) {
            return 1.0F;
        }
        if (block instanceof BedBlock) {
            return 0.5F;
        }
        return 1.0F;
    }

    /**
     * Armor reduction mirroring vanilla's damage pipeline; magic (protection)
     * enchantments are not included in the prediction.
     */
    public static float effectiveDamage(Player player, DamageSource source, float damage) {
        if (player.isDeadOrDying() || player.getAbilities().invulnerable) {
            return 0.0F;
        }

        float armor = player.getArmorValue();
        float toughness = (float) player.getAttributeValue(Attributes.ARMOR_TOUGHNESS);
        float armorPoints =
                Math.min(20.0F, Math.max(armor / 5.0F, armor - damage / (2.0F + toughness / 4.0F)));
        return Math.max(0.0F, damage * (1.0F - armorPoints / 25.0F));
    }

    public static float fallDamage(Minecraft client, Player player) {
        BlockPos landing = LandingPrediction.landingBlock(player);
        float multiplier = fallDamageMultiplier(client, landing);
        if (multiplier <= 0.0F) {
            return 0.0F;
        }

        int damage = Math.max(0, Mth.ceil((player.fallDistance - 3.0F) * multiplier));
        if (damage <= 0) {
            return 0.0F;
        }

        return effectiveDamage(player, player.damageSources().fall(), damage);
    }

    public static double meleeDamage(
            Minecraft client, LivingEntity target, double charge, boolean critical) {
        double attackDamage =
                Math.max(0.0D, client.player.getAttributeValue(Attributes.ATTACK_DAMAGE));
        double rawDamage = attackDamage * (0.2D + charge * charge * 0.8D);
        if (critical) {
            rawDamage *= 1.5D;
        }

        double armor = Math.max(0.0D, target.getArmorValue());
        double toughness = Math.max(0.0D, target.getAttributeValue(Attributes.ARMOR_TOUGHNESS));
        double armorPoints =
                Math.min(
                        20.0D,
                        Math.max(armor / 5.0D, armor - rawDamage / (2.0D + toughness / 4.0D)));
        double damage = rawDamage * (1.0D - armorPoints / 25.0D);
        var resistance = target.getEffect(MobEffects.RESISTANCE);
        if (resistance != null) {
            int amplifier = resistance.getAmplifier() + 1;
            damage *= Math.max(0.0D, 1.0D - amplifier * 0.2D);
        }
        return Math.max(0.0D, damage);
    }
}
