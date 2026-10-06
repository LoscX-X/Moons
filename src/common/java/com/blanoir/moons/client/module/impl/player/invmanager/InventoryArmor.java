package com.blanoir.moons.client.module.impl.player.invmanager;

import com.blanoir.moons.client.utils.inventory.LegacyItems;

import net.minecraft.enchantment.Enchantment;
import net.minecraft.item.ItemArmor;
import net.minecraft.item.ItemStack;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/** Ordinary-hit protection at an assumed six points of damage; ties keep the equipped piece. */
public final class InventoryArmor {

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
        return !LegacyItems.empty(stack) && stack.getItem() instanceof ItemArmor armor
                ? 39 - armor.armorType
                : -1;
    }

    public static List<InventoryAction> plan(
            InventorySnapshot snapshot,
            BitSet blocked,
            List<InventoryRole> roles,
            boolean protectSpecial,
            BitSet locked) {
        var actions = new ArrayList<InventoryAction>();
        for (int target = 36; target < 40; target++) {
            ItemStack current = snapshot.item(target);
            if (blocked.get(target)
                    || locked.get(target - 36)
                    || snapshot.menuSlot(target) < 0
                    || protectSpecial && !InventoryItems.protection(current).isEmpty()) continue;
            int best = target;
            for (int source = 0; source < 36; source++) {
                ItemStack candidate = snapshot.item(source);
                if (blocked.get(source)
                        || snapshot.menuSlot(source) < 0
                        || InventoryPlan.role(roles, source) == InventoryRole.LOCKED
                        || slot(candidate) != target
                        || candidate.stackSize != 1
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
                                LegacyItems.copy(snapshot.item(best)),
                                LegacyItems.copy(current),
                                (LegacyItems.empty(current) ? "Equip " : "Upgrade ")
                                        + label(target)));
        }
        actions.sort(
                (a, b) -> {
                    int empty =
                            Boolean.compare(
                                    LegacyItems.empty(b.beforeTarget()),
                                    LegacyItems.empty(a.beforeTarget()));
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
        if (LegacyItems.empty(current)) return true;
        boolean worn = InventoryItems.nearlyBroken(candidate);
        if (worn != InventoryItems.nearlyBroken(current)) return !worn;
        double difference = damage(snapshot, target, current) - damage(snapshot, target, candidate);
        if (Math.abs(difference) > EPSILON) return difference > 0;
        if (equipped) return false;
        int utility = Integer.compare(utility(candidate), utility(current));
        if (utility != 0) return utility > 0;
        return candidate.getMaxDamage() - candidate.getItemDamage()
                > current.getMaxDamage() - current.getItemDamage();
    }

    public static double damage(InventorySnapshot snapshot, int target, ItemStack replacement) {
        double armor = 0;
        int protection = 0;
        for (int i = 36; i < 40; i++) {
            ItemStack stack = i == target ? replacement : snapshot.item(i);
            armor += armor(stack);
            int level = InventoryItems.enchant(stack, Enchantment.protection);
            if (level > 0)
                protection +=
                        Enchantment.protection.calcModifierDamage(
                                level, net.minecraft.util.DamageSource.generic);
        }
        double reduction = Math.min(20, armor);
        // Vanilla 1.8 rolls EPF after summing it. Average the capped discrete
        // distribution so comparing otherwise equal armor cannot fluctuate.
        int epf = Math.min(25, protection);
        int base = (epf + 1) / 2;
        double expected = 0;
        for (int bonus = 0; bonus <= epf / 2; bonus++) expected += Math.min(20, base + bonus);
        expected /= epf / 2 + 1;
        return 6 * (1 - reduction / 25) * (1 - expected * .04);
    }

    private static double armor(ItemStack stack) {
        return !LegacyItems.empty(stack) && stack.getItem() instanceof ItemArmor armor
                ? armor.damageReduceAmount
                : 0;
    }

    private static int utility(ItemStack stack) {
        return InventoryItems.enchant(stack, Enchantment.featherFalling) * 8
                + InventoryItems.enchant(stack, Enchantment.unbreaking) * 3
                + InventoryItems.enchant(stack, Enchantment.respiration)
                + InventoryItems.enchant(stack, Enchantment.depthStrider)
                + InventoryItems.enchant(stack, Enchantment.fireProtection)
                + InventoryItems.enchant(stack, Enchantment.blastProtection)
                + InventoryItems.enchant(stack, Enchantment.projectileProtection);
    }
}
