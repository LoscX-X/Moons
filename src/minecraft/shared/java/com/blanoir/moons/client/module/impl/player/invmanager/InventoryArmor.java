package com.blanoir.moons.client.module.impl.player.invmanager;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.enchantment.Enchantments;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/** Ordinary-hit protection at an assumed six points of damage; ties keep the equipped piece. */
public final class InventoryArmor {
    private static final EquipmentSlot[] SLOTS = {
        EquipmentSlot.FEET, EquipmentSlot.LEGS, EquipmentSlot.CHEST, EquipmentSlot.HEAD
    };
    private static final double EPSILON = 1.0E-6;

    private InventoryArmor() {}

    public static String label(int index) {
        return switch (index) {
            case 36 -> "Boots";
            case 37 -> "Leggings";
            case 38 -> "Chestplate";
            case 39 -> "Helmet";
            default -> "Armor";
        };
    }

    public static int slot(ItemStack stack) {
        var equippable = stack.get(DataComponents.EQUIPPABLE);
        if (equippable == null
                || stack.is(Items.ELYTRA)
                || !net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                        .get(net.minecraft.resources.Identifier.parse("minecraft:player"))
                        .map(equippable::canBeEquippedBy)
                        .orElse(false)) return -1;
        for (int i = 0; i < SLOTS.length; i++)
            if (equippable.slot() == SLOTS[i] && armor(stack, SLOTS[i], false) > 0) return 36 + i;
        return -1;
    }

    public static List<InventoryAction> plan(
            InventorySnapshot snapshot,
            BitSet blocked,
            List<InventoryRole> roles,
            boolean protectSpecial,
            boolean keepElytra,
            BitSet locked) {
        var actions = new ArrayList<InventoryAction>();
        for (int target = 36; target < 40; target++) {
            ItemStack current = snapshot.item(target);
            if (blocked.get(target)
                    || locked.get(target - 36)
                    || snapshot.menuSlot(target) < 0
                    || keepElytra && current.is(Items.ELYTRA)
                    || protectSpecial && !InventoryItems.protection(current).isEmpty()) continue;
            int best = target;
            for (int source = 0; source < 36; source++) {
                ItemStack candidate = snapshot.item(source);
                if (blocked.get(source)
                        || snapshot.menuSlot(source) < 0
                        || InventoryPlan.role(roles, source) == InventoryRole.LOCKED
                        || slot(candidate) != target
                        || candidate.getCount() != 1
                        || InventoryItems.enchant(candidate, Enchantments.BINDING_CURSE) > 0
                        || protectSpecial && !InventoryItems.protection(candidate).isEmpty())
                    continue;
                if (better(snapshot, target, candidate, snapshot.item(best), best == target))
                    best = source;
            }
            if (best != target)
                actions.add(
                        new InventoryAction(
                                best,
                                target,
                                snapshot.item(best).copy(),
                                current.copy(),
                                (current.isEmpty() ? "Equip " : "Upgrade ") + label(target)));
        }
        actions.sort(
                (a, b) -> {
                    int empty =
                            Boolean.compare(b.beforeTarget().isEmpty(), a.beforeTarget().isEmpty());
                    if (empty != 0) return empty;
                    double aGain =
                            damage(snapshot, a.target(), a.beforeTarget())
                                    - damage(snapshot, a.target(), a.beforeSource());
                    double bGain =
                            damage(snapshot, b.target(), b.beforeTarget())
                                    - damage(snapshot, b.target(), b.beforeSource());
                    int gain = Double.compare(bGain, aGain);
                    return gain != 0 ? gain : Integer.compare(b.target(), a.target());
                });
        return List.copyOf(actions);
    }

    private static boolean better(
            InventorySnapshot snapshot,
            int target,
            ItemStack candidate,
            ItemStack current,
            boolean equipped) {
        if (current.isEmpty()) return true;
        boolean worn = InventoryItems.nearlyBroken(candidate);
        if (worn != InventoryItems.nearlyBroken(current)) return !worn;
        double difference = damage(snapshot, target, current) - damage(snapshot, target, candidate);
        if (Math.abs(difference) > EPSILON) return difference > 0;
        if (equipped) return false;
        int utility = Integer.compare(utility(candidate), utility(current));
        if (utility != 0) return utility > 0;
        return candidate.getMaxDamage() - candidate.getDamageValue()
                > current.getMaxDamage() - current.getDamageValue();
    }

    public static double damage(InventorySnapshot snapshot, int target, ItemStack replacement) {
        double armor = 0, toughness = 0;
        int protection = 0;
        for (int i = 36; i < 40; i++) {
            ItemStack stack = i == target ? replacement : snapshot.item(i);
            armor += armor(stack, SLOTS[i - 36], false);
            toughness += armor(stack, SLOTS[i - 36], true);
            protection += InventoryItems.enchant(stack, Enchantments.PROTECTION);
        }
        double reduction = Math.min(20, Math.max(armor * .2, armor - 6 / (2 + toughness / 4)));
        return 6 * (1 - reduction / 25) * (1 - Math.min(20, protection) * .04);
    }

    private static double armor(ItemStack stack, EquipmentSlot slot, boolean toughness) {
        return stack.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY)
                .compute(toughness ? Attributes.ARMOR_TOUGHNESS : Attributes.ARMOR, 0, slot);
    }

    private static int utility(ItemStack stack) {
        return InventoryItems.enchant(stack, Enchantments.FEATHER_FALLING) * 8
                + InventoryItems.enchant(stack, Enchantments.UNBREAKING) * 3
                + InventoryItems.enchant(stack, Enchantments.MENDING) * 3
                + InventoryItems.enchant(stack, Enchantments.RESPIRATION)
                + InventoryItems.enchant(stack, Enchantments.DEPTH_STRIDER)
                + InventoryItems.enchant(stack, Enchantments.FIRE_PROTECTION)
                + InventoryItems.enchant(stack, Enchantments.BLAST_PROTECTION)
                + InventoryItems.enchant(stack, Enchantments.PROJECTILE_PROTECTION);
    }
}
