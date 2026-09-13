package com.blanoir.moons.client.module.impl.player.invmanager;

import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;

import java.util.Objects;

/** Conservative client-visible classification. No unknown item is considered disposable. */
public final class InventoryItems {
    private InventoryItems() {}

    public static String protection(ItemStack stack) {
        if (stack.isEmpty()) return "";
        if (stack.has(DataComponents.CUSTOM_NAME)) return "Named item";
        var lore = stack.get(DataComponents.LORE);
        if (lore != null && !lore.lines().isEmpty()) return "Item with lore";
        var data = stack.get(DataComponents.CUSTOM_DATA);
        if (data != null && !data.isEmpty()) return "Custom item data";
        if (stack.has(DataComponents.CUSTOM_MODEL_DATA)) return "Custom model";
        if (!Objects.equals(
                stack.get(DataComponents.ITEM_MODEL),
                stack.getItem().components().get(DataComponents.ITEM_MODEL)))
            return "Custom item model";
        if (!Objects.equals(
                stack.get(DataComponents.ATTRIBUTE_MODIFIERS),
                stack.getItem().components().get(DataComponents.ATTRIBUTE_MODIFIERS)))
            return "Custom attributes";
        if (stack.has(DataComponents.CONTAINER) || stack.has(DataComponents.BUNDLE_CONTENTS))
            return "Container item";
        return "";
    }

    public static boolean matches(InventoryRole role, ItemStack stack) {
        if (stack.isEmpty()) return false;
        return switch (role) {
            case FREE, LOCKED, CUSTOM -> false;
            case SWORD -> stack.is(ItemTags.SWORDS);
            // Specialized pickaxes have their own roles and are never silently replaced by a
            // plain pickaxe or each other.
            case PICKAXE ->
                    stack.is(ItemTags.PICKAXES)
                            && enchant(stack, Enchantments.SILK_TOUCH) == 0
                            && enchant(stack, Enchantments.FORTUNE) == 0;
            case SILK_PICKAXE ->
                    stack.is(ItemTags.PICKAXES) && enchant(stack, Enchantments.SILK_TOUCH) > 0;
            case FORTUNE_PICKAXE ->
                    stack.is(ItemTags.PICKAXES) && enchant(stack, Enchantments.FORTUNE) > 0;
            case AXE -> stack.is(ItemTags.AXES);
            case SHOVEL -> stack.is(ItemTags.SHOVELS);
            case BOW -> stack.is(Items.BOW);
            case CROSSBOW -> stack.is(Items.CROSSBOW);
            case BLOCK -> buildingBlock(stack);
            case FOOD ->
                    stack.has(DataComponents.FOOD)
                            && !stack.is(Items.GOLDEN_APPLE)
                            && !stack.is(Items.ENCHANTED_GOLDEN_APPLE)
                            && !stack.is(Items.ROTTEN_FLESH)
                            && !stack.is(Items.POISONOUS_POTATO)
                            && !stack.is(Items.SPIDER_EYE)
                            && !stack.is(Items.PUFFERFISH)
                            && !stack.is(Items.CHICKEN)
                            && !stack.is(Items.SUSPICIOUS_STEW)
                            && !stack.is(Items.CHORUS_FRUIT);
            case GOLDEN_APPLE ->
                    stack.is(Items.GOLDEN_APPLE) || stack.is(Items.ENCHANTED_GOLDEN_APPLE);
            case PEARL -> stack.is(Items.ENDER_PEARL);
            case THROWABLE -> stack.is(Items.EGG) || stack.is(Items.SNOWBALL);
            case WATER -> stack.is(Items.WATER_BUCKET);
            case LAVA -> stack.is(Items.LAVA_BUCKET);
            case SHIELD -> stack.is(Items.SHIELD);
            case TOTEM -> stack.is(Items.TOTEM_OF_UNDYING);
        };
    }

    private static boolean buildingBlock(ItemStack stack) {
        return stack.getItem() instanceof BlockItem;
    }

    /** Quality excludes stack size. A partly used stack already in place should stay there. */
    public static double quality(InventoryRole role, ItemStack stack) {
        return switch (role) {
            case SWORD ->
                    stack.getOrDefault(
                                            DataComponents.ATTRIBUTE_MODIFIERS,
                                            ItemAttributeModifiers.EMPTY)
                                    .compute(Attributes.ATTACK_DAMAGE, 1, EquipmentSlot.MAINHAND)
                            + enchant(stack, Enchantments.SHARPNESS) * .5
                            + enchant(stack, Enchantments.FIRE_ASPECT) * .1;
            case PICKAXE, SILK_PICKAXE, FORTUNE_PICKAXE, AXE, SHOVEL ->
                    toolSpeed(stack) + enchant(stack, Enchantments.EFFICIENCY) * 1.5;
            case BOW ->
                    enchant(stack, Enchantments.POWER) * 2
                            + enchant(stack, Enchantments.INFINITY)
                            + enchant(stack, Enchantments.FLAME) * .5;
            case CROSSBOW ->
                    enchant(stack, Enchantments.QUICK_CHARGE) * 2
                            + enchant(stack, Enchantments.MULTISHOT)
                            + enchant(stack, Enchantments.PIERCING) * .5;
            case FOOD -> stack.get(DataComponents.FOOD).nutrition();
            default -> 0;
        };
    }

    public static boolean nearlyBroken(ItemStack stack) {
        return stack.isDamageableItem()
                && stack.getMaxDamage() - stack.getDamageValue()
                        <= Math.max(5, stack.getMaxDamage() / 20);
    }

    private static double toolSpeed(ItemStack stack) {
        var tool = stack.get(DataComponents.TOOL);
        if (tool == null) return 0;
        double speed = tool.defaultMiningSpeed();
        for (var rule : tool.rules()) speed = Math.max(speed, rule.speed().orElse(0f));
        return speed;
    }

    public static int enchant(ItemStack stack, ResourceKey<Enchantment> enchantment) {
        for (var entry : stack.getEnchantments().entrySet())
            if (entry.getKey().is(enchantment)) return entry.getIntValue();
        return 0;
    }
}
