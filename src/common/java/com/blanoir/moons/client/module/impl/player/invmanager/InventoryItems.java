package com.blanoir.moons.client.module.impl.player.invmanager;

import com.blanoir.moons.client.utils.inventory.LegacyItems;

import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.*;

/** Conservative classification of the actual item and NBT sent by a 1.8.9 server. */
public final class InventoryItems {
    private InventoryItems() {}

    public static String protection(ItemStack stack) {
        if (LegacyItems.empty(stack)) return "";
        if (stack.hasDisplayName()) return "Named item";
        if (!stack.hasTagCompound()) return "";
        var tag = stack.getTagCompound();
        if (tag.getCompoundTag("display").hasKey("Lore", 9)) return "Item with lore";
        if (tag.hasKey("AttributeModifiers", 9)) return "Custom attributes";
        for (String key : tag.getKeySet())
            if (!key.equals("ench") && !key.equals("RepairCost") && !key.equals("display"))
                return "Custom item data";
        return "";
    }

    public static boolean matches(InventoryRole role, ItemStack stack) {
        if (LegacyItems.empty(stack)) return false;
        Item item = stack.getItem();
        return switch (role) {
            case FREE, LOCKED, CUSTOM -> false;
            case SWORD -> item instanceof ItemSword;
            case PICKAXE ->
                    item instanceof ItemPickaxe
                            && enchant(stack, Enchantment.silkTouch) == 0
                            && enchant(stack, Enchantment.fortune) == 0;
            case SILK_PICKAXE ->
                    item instanceof ItemPickaxe && enchant(stack, Enchantment.silkTouch) > 0;
            case FORTUNE_PICKAXE ->
                    item instanceof ItemPickaxe && enchant(stack, Enchantment.fortune) > 0;
            case AXE -> item instanceof ItemAxe;
            case SHOVEL -> item instanceof ItemSpade;
            case BOW -> item == Items.bow;
            case BLOCK -> item instanceof ItemBlock;
            case FOOD ->
                    item instanceof ItemFood
                            && item != Items.golden_apple
                            && item != Items.rotten_flesh
                            && item != Items.poisonous_potato
                            && item != Items.spider_eye
                            && item != Items.chicken
                            && !(item == Items.fish && stack.getMetadata() == 3);
            case GOLDEN_APPLE -> item == Items.golden_apple;
            case PEARL -> item == Items.ender_pearl;
            case THROWABLE -> item == Items.egg || item == Items.snowball;
            case WATER -> item == Items.water_bucket;
            case LAVA -> item == Items.lava_bucket;
        };
    }

    public static double quality(InventoryRole role, ItemStack stack) {
        if (LegacyItems.empty(stack)) return 0;
        return switch (role) {
            case SWORD ->
                    attackDamage(stack)
                            + enchant(stack, Enchantment.sharpness) * 1.25
                            + enchant(stack, Enchantment.fireAspect) * .1;
            case PICKAXE, SILK_PICKAXE, FORTUNE_PICKAXE, AXE, SHOVEL ->
                    toolSpeed(stack) + enchant(stack, Enchantment.efficiency) * 1.5;
            case BOW ->
                    enchant(stack, Enchantment.power) * 2
                            + enchant(stack, Enchantment.infinity)
                            + enchant(stack, Enchantment.flame) * .5;
            case FOOD -> stack.getItem() instanceof ItemFood food ? food.getHealAmount(stack) : 0;
            default -> 0;
        };
    }

    public static double attackDamage(ItemStack stack) {
        double value = 1;
        for (var modifier : stack.getAttributeModifiers().get("generic.attackDamage"))
            if (modifier.getOperation() == 0) value += modifier.getAmount();
        return value;
    }

    public static boolean nearlyBroken(ItemStack stack) {
        return !LegacyItems.empty(stack)
                && stack.isItemStackDamageable()
                && stack.getMaxDamage() - stack.getItemDamage()
                        <= Math.max(5, stack.getMaxDamage() / 20);
    }

    private static double toolSpeed(ItemStack stack) {
        return Math.max(
                stack.getStrVsBlock(Blocks.stone),
                Math.max(stack.getStrVsBlock(Blocks.log), stack.getStrVsBlock(Blocks.dirt)));
    }

    public static int enchant(ItemStack stack, Enchantment enchantment) {
        return LegacyItems.empty(stack)
                ? 0
                : EnchantmentHelper.getEnchantmentLevel(enchantment.effectId, stack);
    }
}
