package com.blanoir.moons.client.utils.prediction;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.CombatRules;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.minecart.MinecartTNT;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ServerExplosion;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** Existing melee, explosion and fall damage estimates, independent of module settings. */
public final class DamagePrediction {
    private static final EquipmentSlot[] ARMOR = {
        EquipmentSlot.FEET, EquipmentSlot.LEGS, EquipmentSlot.CHEST, EquipmentSlot.HEAD
    };

    private DamagePrediction() {}

    /** Client-visible countdown. Crystals can be detonated immediately by another player. */
    public static int explosionTicks(Entity entity) {
        if (entity == null || entity.isRemoved()) return Integer.MAX_VALUE;
        if (entity instanceof EndCrystal) return 0;
        if (entity instanceof PrimedTnt tnt) return Math.max(0, tnt.getFuse());
        if (entity instanceof MinecartTNT cart) {
            if (cart.horizontalCollision
                    && cart.getDeltaMovement().horizontalDistanceSqr() >= 0.01D) return 0;
            return cart.isPrimed() ? Math.max(0, cart.getFuse()) : Integer.MAX_VALUE;
        }
        if (entity instanceof Creeper creeper) {
            if (!creeper.isAlive() || (!creeper.isIgnited() && creeper.getSwellDir() <= 0))
                return Integer.MAX_VALUE;
            // Vanilla clients use the 30-tick fuse; getSwelling divides progress by 28.
            return Math.max(0, Mth.ceil(30.0F - creeper.getSwelling(1.0F) * 28.0F));
        }
        return Integer.MAX_VALUE;
    }

    public static float explosionDamageFromEntity(Minecraft client, Entity entity) {
        if (explosionTicks(entity) == Integer.MAX_VALUE) return 0;
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
            // Vanilla adds a random speed-dependent term. Use its bounded upper limit.
            float power =
                    4.0F
                            + (float) Math.min(5.0D, entity.getDeltaMovement().horizontalDistance())
                                    * 1.5F;
            return explosionDamage(
                    client,
                    entity.position(),
                    power,
                    power * 2,
                    power * power * 4,
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

    /** Client-side vanilla reductions, including synchronized armor enchantments. */
    public static float effectiveDamage(Player player, DamageSource source, float damage) {
        if (player.isDeadOrDying() || player.getAbilities().invulnerable) {
            return 0.0F;
        }

        if (source.scalesWithDifficulty())
            damage = scaleForDifficulty(damage, player.level().getDifficulty());
        if (!source.is(DamageTypeTags.BYPASSES_ARMOR))
            damage =
                    CombatRules.getDamageAfterAbsorb(
                            player,
                            damage,
                            source,
                            player.getArmorValue(),
                            (float) player.getAttributeValue(Attributes.ARMOR_TOUGHNESS));
        if (!source.is(DamageTypeTags.BYPASSES_EFFECTS)) {
            var resistance = player.getEffect(MobEffects.RESISTANCE);
            if (resistance != null && !source.is(DamageTypeTags.BYPASSES_RESISTANCE))
                damage = Math.max(0, damage * (25 - (resistance.getAmplifier() + 1) * 5) / 25.0F);
            if (!source.is(DamageTypeTags.BYPASSES_ENCHANTMENTS))
                damage = CombatRules.getDamageAfterMagicAbsorb(damage, protection(player, source));
        }
        return Math.max(0, damage);
    }

    static float scaleForDifficulty(float damage, net.minecraft.world.Difficulty difficulty) {
        return switch (difficulty) {
            case PEACEFUL -> 0;
            case EASY -> Math.min(damage / 2 + 1, damage);
            case NORMAL -> damage;
            case HARD -> damage * 3 / 2;
        };
    }

    static int protection(Player player, DamageSource source) {
        int result = 0;
        for (EquipmentSlot slot : ARMOR) {
            for (var entry : player.getItemBySlot(slot).getEnchantments().entrySet()) {
                var enchantment = entry.getKey();
                if (!enchantment.value().matchingSlot(slot)) continue;
                int level = Math.max(0, entry.getIntValue());
                if (enchantment.is(Enchantments.PROTECTION)) result += level;
                else if (enchantment.is(Enchantments.BLAST_PROTECTION)
                        && source.is(DamageTypeTags.IS_EXPLOSION)) result += level * 2;
                else if (enchantment.is(Enchantments.FEATHER_FALLING)
                        && source.is(DamageTypeTags.IS_FALL)) result += level * 3;
                else if (enchantment.is(Enchantments.FIRE_PROTECTION)
                        && source.is(DamageTypeTags.IS_FIRE)) result += level * 2;
                else if (enchantment.is(Enchantments.PROJECTILE_PROTECTION)
                        && source.is(DamageTypeTags.IS_PROJECTILE)) result += level * 2;
            }
        }
        return Math.min(20, result);
    }

    public static float fallDamage(Minecraft client, Player player) {
        BlockPos landing = LandingPrediction.landingBlock(player);
        float multiplier = fallDamageMultiplier(client, landing);
        if (multiplier <= 0.0F) {
            return 0.0F;
        }

        return fallDamage(player, player.fallDistance, multiplier);
    }

    public static float fallDamage(Player player, double distance, float multiplier) {
        int damage = rawFallDamage(player, distance, multiplier);
        if (damage <= 0) {
            return 0.0F;
        }

        return effectiveDamage(player, player.damageSources().fall(), damage);
    }

    static int rawFallDamage(Player player, double distance, float multiplier) {
        return Math.max(
                0,
                Mth.floor(
                        (distance
                                        + 1.0E-6D
                                        - player.getAttributeValue(Attributes.SAFE_FALL_DISTANCE))
                                * multiplier
                                * player.getAttributeValue(Attributes.FALL_DAMAGE_MULTIPLIER)));
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
