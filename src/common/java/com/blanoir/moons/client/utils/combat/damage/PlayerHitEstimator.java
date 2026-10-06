package com.blanoir.moons.client.utils.combat.damage;

import com.blanoir.moons.client.access.GameAccess;
import com.blanoir.moons.client.config.feature.HitEstimateSettings;

import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.CombatRules;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MaceItem;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;

/** Estimates fully charged melee hits needed to defeat a player. */
public final class PlayerHitEstimator {
    public static final int UNKNOWN = -1;
    public static final int UNREACHABLE = Integer.MAX_VALUE;
    private static final float MIN_DAMAGE = 1.0E-4F;
    private static final EquipmentSlot[] PLAYER_ARMOR_SLOTS = {
        EquipmentSlot.FEET, EquipmentSlot.LEGS, EquipmentSlot.CHEST, EquipmentSlot.HEAD
    };

    private PlayerHitEstimator() {}

    public static int hitsToKill(Minecraft client, Player target, float resolvedHealth) {
        if (client == null || client.player == null || client.level == null || target == null) {
            return UNKNOWN;
        }

        float health =
                Math.max(0.0F, resolvedHealth) + Math.max(0.0F, target.getAbsorptionAmount());
        if (health <= 0.0F) {
            return 0;
        }

        float damage = estimatedDamage(client, target);
        if (!Float.isFinite(damage) || damage <= MIN_DAMAGE) {
            return UNREACHABLE;
        }
        return Math.max(1, (int) Math.ceil(health / damage));
    }

    public static String text(Minecraft client, Player target, float resolvedHealth) {
        int hits = hitsToKill(client, target, resolvedHealth);
        if (hits == UNKNOWN) {
            return "Hit: ?";
        }
        if (hits == UNREACHABLE) {
            return "Hit: ∞";
        }
        return "Hit: " + hits;
    }

    private static float estimatedDamage(Minecraft client, Player target) {
        ItemStack weapon = client.player.getWeaponItem();
        boolean maceSmash =
                weapon.getItem() instanceof MaceItem && MaceItem.canSmashAttack(client.player);
        // This is the same source selection used by Player.attack, including
        // item-provided damage types and the mace-smash source.
        DamageSource source = GameAccess.weaponDamageSource(weapon, client.player);
        float baseDamage = currentWeaponAttackDamage(client.player, weapon);
        baseDamage += weapon.getItem().getAttackDamageBonus(target, baseDamage, source);

        // The server's enchantment effect dispatcher is ServerLevel-only. Read
        // the synchronized item components directly and mirror the vanilla
        // data-pack values; never silently drop all enchantments just because a
        // client registry wrapper is briefly unavailable after joining.
        int sharpness = enchantmentLevel(weapon, Enchantments.SHARPNESS);
        float magicBoost = 0.0F;
        if (sharpness > 0) {
            magicBoost += 0.5F + sharpness * 0.5F;
        }
        int breach = enchantmentLevel(weapon, Enchantments.BREACH);
        int density = enchantmentLevel(weapon, Enchantments.DENSITY);
        if (density > 0 && maceSmash) {
            magicBoost += (float) (0.5D * density * client.player.fallDistance);
        }

        int protection = 0;
        for (EquipmentSlot slot : PLAYER_ARMOR_SLOTS) {
            protection += enchantmentLevel(target.getItemBySlot(slot), Enchantments.PROTECTION);
        }
        // Vanilla multiplies only base damage for a critical, then adds the
        // enchantment boost. Armor is nonlinear, so mitigate normal and crit
        // separately before weighting them by the selected critical rate.
        float normalDamage =
                mitigatedDamage(baseDamage + magicBoost, target, source, breach, protection);
        float criticalDamage =
                mitigatedDamage(baseDamage * 1.5F + magicBoost, target, source, breach, protection);
        double criticalRate = HitEstimateSettings.criticalRate();
        return Math.max(
                0.0F,
                (float) (normalDamage * (1.0D - criticalRate) + criticalDamage * criticalRate));
    }

    /**
     * Rebuilds the main-hand attack attribute from the stack that is selected
     * right now. LivingEntity updates equipment attribute modifiers from its
     * equipment-change tick; immediately after a hotbar switch the aggregate
     * player attribute can therefore still describe the previous weapon.
     *
     * This follows ItemAttributeModifiers.compute and includes the two
     * vanilla attack-damage effects before applying the stack operations.
     */
    private static float currentWeaponAttackDamage(Player player, ItemStack weapon) {
        double base = player.getAttributeBaseValue(Attributes.ATTACK_DAMAGE);
        MobEffectInstance strength = player.getEffect(MobEffects.STRENGTH);
        if (strength != null) {
            base += 3.0D * (strength.getAmplifier() + 1.0D);
        }
        MobEffectInstance weakness = player.getEffect(MobEffects.WEAKNESS);
        if (weakness != null) {
            base -= 4.0D * (weakness.getAmplifier() + 1.0D);
        }

        ItemAttributeModifiers modifiers =
                weapon.getOrDefault(
                        DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
        double value = modifiers.compute(Attributes.ATTACK_DAMAGE, base, EquipmentSlot.MAINHAND);
        return (float) Math.max(0.0D, Attributes.ATTACK_DAMAGE.value().sanitizeValue(value));
    }

    private static float mitigatedDamage(
            float damage, Player target, DamageSource source, int breachLevel, int protection) {
        if (!source.is(DamageTypeTags.BYPASSES_ARMOR)) {
            damage =
                    damageAfterArmor(
                            damage,
                            target.getArmorValue(),
                            (float) target.getAttributeValue(Attributes.ARMOR_TOUGHNESS),
                            breachLevel);
        }
        if (!source.is(DamageTypeTags.BYPASSES_EFFECTS)) {
            MobEffectInstance resistance = target.getEffect(MobEffects.RESISTANCE);
            if (resistance != null && !source.is(DamageTypeTags.BYPASSES_RESISTANCE)) {
                damage *= Math.max(0.0F, 1.0F - (resistance.getAmplifier() + 1) * 0.2F);
            }
            if (!source.is(DamageTypeTags.BYPASSES_ENCHANTMENTS)) {
                damage = CombatRules.getDamageAfterMagicAbsorb(damage, protection);
            }
        }
        return damage;
    }

    private static int enchantmentLevel(ItemStack stack, ResourceKey<Enchantment> enchantment) {
        if (stack == null || stack.isEmpty()) return 0;
        for (var entry : stack.getEnchantments().entrySet()) {
            if (entry.getKey().is(enchantment)) {
                return Math.max(0, entry.getIntValue());
            }
        }
        return 0;
    }

    private static float damageAfterArmor(
            float damage, float armor, float toughness, int breachLevel) {
        float toughnessFactor = 2.0F + toughness / 4.0F;
        float effectiveArmor = Mth.clamp(armor - damage / toughnessFactor, armor * 0.2F, 20.0F);
        float armorEffectiveness = effectiveArmor / 25.0F;
        armorEffectiveness = Mth.clamp(armorEffectiveness - 0.15F * breachLevel, 0.0F, 1.0F);
        return damage * (1.0F - armorEffectiveness);
    }
}
