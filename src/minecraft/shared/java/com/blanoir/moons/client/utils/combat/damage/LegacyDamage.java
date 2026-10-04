package com.blanoir.moons.client.utils.combat.damage;

import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.ai.attributes.AttributeModifier;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.potion.Potion;
import net.minecraft.util.DamageSource;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Native 1.8.9 damage rules. Enchantment protection uses its finite random distribution's mean. */
public final class LegacyDamage {
    private static final UUID VANILLA_WEAPON =
            UUID.fromString("CB3F55D3-645C-4F38-A497-9C13A33DB5CF");

    private LegacyDamage() {}

    public static double attackDamage(EntityPlayer player) {
        var attribute = player.getEntityAttribute(SharedMonsterAttributes.attackDamage);
        if (attribute == null) return 1;
        ItemStack held = player.getHeldItem();
        var selected =
                held == null
                        ? List.<AttributeModifier>of()
                        : new ArrayList<>(
                                held.getAttributeModifiers()
                                        .get(
                                                SharedMonsterAttributes.attackDamage
                                                        .getAttributeUnlocalizedName()));
        List<AttributeModifier> modifiers = new ArrayList<>();
        for (AttributeModifier modifier : attribute.func_111122_c()) {
            if (VANILLA_WEAPON.equals(modifier.getID())
                    || selected.stream()
                            .anyMatch(current -> current.getID().equals(modifier.getID())))
                continue;
            modifiers.add(modifier);
        }
        modifiers.addAll(selected);
        double base = attribute.getBaseValue();
        for (var modifier : modifiers)
            if (modifier.getOperation() == 0) base += modifier.getAmount();
        double result = base;
        for (var modifier : modifiers)
            if (modifier.getOperation() == 1) result += base * modifier.getAmount();
        for (var modifier : modifiers)
            if (modifier.getOperation() == 2) result *= 1 + modifier.getAmount();
        return Math.max(0, SharedMonsterAttributes.attackDamage.clampValue(result));
    }

    public static float melee(EntityPlayer attacker, EntityLivingBase target, boolean critical) {
        float base = (float) attackDamage(attacker);
        // 1.8.9 Sharpness contributes 1.25 per level, outside the critical multiplier.
        float enchant =
                EnchantmentHelper.getModifierForCreature(
                        attacker.getHeldItem(), target.getCreatureAttribute());
        return mitigate(
                target,
                DamageSource.causePlayerDamage(attacker),
                base * (critical ? 1.5F : 1F) + enchant);
    }

    public static float mitigate(EntityLivingBase target, DamageSource source, float damage) {
        if (target == null || !target.isEntityAlive() || damage <= 0) return 0;
        if (target instanceof EntityPlayer player) {
            if (player.capabilities.disableDamage && !source.canHarmInCreative()) return 0;
            if (source.isDifficultyScaled())
                damage =
                        switch (target.worldObj.getDifficulty()) {
                            case PEACEFUL -> 0;
                            case EASY -> Math.min(damage / 2 + 1, damage);
                            case NORMAL -> damage;
                            case HARD -> damage * 1.5F;
                        };
            if (damage <= 0) return 0;
            if (!source.isUnblockable() && player.isBlocking()) damage = (1 + damage) * .5F;
        }
        if (source.isFireDamage() && target.isPotionActive(Potion.fireResistance)) return 0;
        if (!source.isUnblockable())
            damage *= (25 - Math.clamp(target.getTotalArmorValue(), 0, 20)) / 25F;
        if (!source.isDamageAbsolute()) {
            var resistance = target.getActivePotionEffect(Potion.resistance);
            if (resistance != null && source != DamageSource.outOfWorld)
                damage *= Math.max(0, 25 - (resistance.getAmplifier() + 1) * 5) / 25F;
            damage *= 1 - protection(target, source) / 25F;
        }
        return Math.max(0, damage);
    }

    public static float protection(EntityLivingBase target, DamageSource source) {
        int sum = 0;
        for (int slot = 0; slot < 4; slot++) {
            ItemStack armor = target.getCurrentArmor(slot);
            if (armor == null) continue;
            for (var entry : EnchantmentHelper.getEnchantments(armor).entrySet()) {
                Enchantment enchantment = Enchantment.getEnchantmentById(entry.getKey());
                if (enchantment != null)
                    sum += enchantment.calcModifierDamage(entry.getValue(), source);
            }
        }
        return expectedProtection(sum);
    }

    public static float expectedProtection(int raw) {
        int capped = Math.clamp(raw, 0, 25);
        int floor = (capped + 1) >> 1, alternatives = (capped >> 1) + 1;
        int total = 0;
        for (int i = 0; i < alternatives; i++) total += Math.min(20, floor + i);
        return (float) total / alternatives;
    }
}
