package com.blanoir.moons.ysm.adapter;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.*;

import java.util.*;

/** Reads the five real equipment slots. The offhand query is explicitly empty in 1.8.9. */
final class YsmEquipmentObservations {
    static final List<String> SLOTS =
            List.of("mainhand", "offhand", "head", "chest", "legs", "feet");

    static boolean validSlot(String slot) {
        return SLOTS.contains(slot.toLowerCase(Locale.ROOT));
    }

    static ItemStack equipment(EntityLivingBase entity, String slot) {
        return switch (slot.toLowerCase(Locale.ROOT)) {
            case "mainhand" -> entity.getHeldItem();
            case "feet" -> entity.getCurrentArmor(0);
            case "legs" -> entity.getCurrentArmor(1);
            case "chest" -> entity.getCurrentArmor(2);
            case "head" -> entity.getCurrentArmor(3);
            default -> null;
        };
    }

    static int sample(Map<String, Object> observations, EntityLivingBase entity) {
        int equipped = 0;
        for (String slot : SLOTS) {
            ItemStack stack = equipment(entity, slot);
            boolean has = stack != null && stack.stackSize > 0;
            observations.put("has_" + slot, has);
            observations.put(
                    slot + "_item",
                    has
                            ? Item.itemRegistry.getNameForObject(stack.getItem()).toString()
                            : "minecraft:air");
            observations.put(slot + "_category", category(stack));
            observations.put(
                    slot + "_use",
                    has ? stack.getItemUseAction().name().toLowerCase(Locale.ROOT) : "none");
            observations.put(
                    slot + "_tags", List.of()); // Vanilla 1.8 has no data-pack registry tags.
            if (has && !slot.endsWith("hand")) equipped++;
        }
        return equipped;
    }

    static String category(ItemStack stack) {
        if (stack == null || stack.stackSize <= 0) return "empty";
        Item item = stack.getItem();
        if (item instanceof ItemPotion && ItemPotion.isSplash(stack.getMetadata()))
            return "throwable_potion";
        if (item instanceof ItemSword) return "sword";
        if (item instanceof ItemAxe) return "axe";
        if (item instanceof ItemPickaxe) return "pickaxe";
        if (item instanceof ItemSpade) return "shovel";
        if (item instanceof ItemHoe) return "hoe";
        return Item.itemRegistry.getNameForObject(item).getResourcePath();
    }
}
