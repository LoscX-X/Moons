package com.blanoir.moons.client.module.impl.player.invclear;

import com.blanoir.moons.client.module.impl.player.invmanager.*;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantments;

import java.util.*;

/** Disposal planning is separate from execution and never treats unknown items as junk. */
public final class InventoryCleanup {
    private static final List<InventoryRole> EQUIPMENT =
            List.of(
                    InventoryRole.SWORD,
                    InventoryRole.PICKAXE,
                    InventoryRole.SILK_PICKAXE,
                    InventoryRole.FORTUNE_PICKAXE,
                    InventoryRole.AXE,
                    InventoryRole.SHOVEL,
                    InventoryRole.BOW,
                    InventoryRole.CROSSBOW);

    public record Drop(int source, ItemStack item, String reason) {}

    private InventoryCleanup() {}

    public static Set<String> itemIds(String text) {
        Set<String> result = new LinkedHashSet<>();
        for (String token : text.split("[,;\\s]+")) {
            if (token.isBlank()) continue;
            Identifier id = Identifier.tryParse(token);
            if (id == null) throw new IllegalArgumentException("Invalid item ID: " + token);
            result.add(id.toString());
        }
        return Set.copyOf(result);
    }

    public static List<Drop> plan(
            InventorySnapshot snapshot,
            List<InventoryRole> roles,
            BitSet blocked,
            boolean protectHotbar,
            boolean protectSpecial,
            boolean equipment,
            Set<String> dropIds,
            Set<String> keepIds) {
        List<Drop> result = new ArrayList<>();
        for (int index = 0; index < 36; index++) {
            ItemStack item = snapshot.item(index);
            String id = BuiltInRegistries.ITEM.getKey(item.getItem()).toString();
            if (item.isEmpty()
                    || snapshot.menuSlot(index) < 0
                    || blocked.get(index)
                    || protectHotbar && index < 9
                    || InventoryPlan.role(roles, index) == InventoryRole.LOCKED
                    || keepIds.contains(id)
                    || protectSpecial && !InventoryItems.protection(item).isEmpty()) continue;
            if (dropIds.contains(id)) {
                result.add(new Drop(index, item.copy(), "Listed junk"));
                continue;
            }
            if (!equipment || !id.startsWith("minecraft:")) continue;
            InventoryRole role = equipmentRole(item);
            int armorSlot = InventoryArmor.slot(item);
            if (role == null && armorSlot < 0) continue;
            long required =
                    role == null
                            ? 1
                            : Math.max(1, roles.stream().filter(value -> value == role).count());
            int better = 0;
            for (int other = 0; other < 41; other++) {
                if (other == index || snapshot.menuSlot(other) < 0) continue;
                ItemStack candidate = snapshot.item(other);
                if (candidate.isEmpty()
                        || dropIds.contains(
                                BuiltInRegistries.ITEM.getKey(candidate.getItem()).toString())
                        || !InventoryItems.protection(candidate).isEmpty()
                        || InventoryItems.enchant(candidate, Enchantments.BINDING_CURSE) > 0
                        || InventoryItems.enchant(candidate, Enchantments.VANISHING_CURSE) > 0)
                    continue;
                if (role != null
                        ? equipmentRole(candidate) != role
                        : InventoryArmor.slot(candidate) != armorSlot) continue;
                if (dominates(
                        snapshot,
                        role,
                        armorSlot,
                        candidate,
                        item,
                        other < index || other == armorSlot)) better++;
            }
            if (better >= required) result.add(new Drop(index, item.copy(), "Surplus equipment"));
        }
        return List.copyOf(result);
    }

    private static InventoryRole equipmentRole(ItemStack stack) {
        for (InventoryRole role : EQUIPMENT) if (InventoryItems.matches(role, stack)) return role;
        return null;
    }

    private static boolean dominates(
            InventorySnapshot snapshot,
            InventoryRole role,
            int armorSlot,
            ItemStack candidate,
            ItemStack item,
            boolean preferredOnTie) {
        // Preserve distinct enchantment utility, such as Silk Touch, Mending or fire protection.
        for (var enchantment : item.getEnchantments().entrySet()) {
            if (candidate.getEnchantments().getLevel(enchantment.getKey())
                    < enchantment.getIntValue()) return false;
        }
        boolean worn = InventoryItems.nearlyBroken(candidate);
        if (worn && !InventoryItems.nearlyBroken(item)) return false;
        double quality =
                role == null
                        ? InventoryArmor.damage(snapshot, armorSlot, item)
                                - InventoryArmor.damage(snapshot, armorSlot, candidate)
                        : InventoryItems.quality(role, candidate)
                                - InventoryItems.quality(role, item);
        if (quality < -1.0E-6) return false;
        int durability =
                (candidate.getMaxDamage() - candidate.getDamageValue())
                        - (item.getMaxDamage() - item.getDamageValue());
        if (durability < 0) return false;
        return quality > 1.0E-6 || durability > 0 || preferredOnTie;
    }
}
