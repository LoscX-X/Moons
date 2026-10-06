package com.blanoir.moons.client.utils.inventory;

import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.init.Blocks;
import net.minecraft.inventory.Container;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

/** Null-safe access to vanilla 1.8.9 stacks. Empty snapshot values are never sent to Minecraft. */
public final class LegacyItems {
    public static final ItemStack EMPTY = new ItemStack(Blocks.air, 0);

    private LegacyItems() {}

    public static net.minecraft.util.ResourceLocation parseId(String value) {
        return value != null && value.matches("(?:[a-z0-9_.-]+:)?[a-z0-9_./-]+")
                ? new net.minecraft.util.ResourceLocation(value)
                : null;
    }

    public static int slotIndex(net.minecraft.inventory.Slot slot) {
        for (int index = 0; index < slot.inventory.getSizeInventory(); index++)
            if (slot.isHere(slot.inventory, index)) return index;
        return -1;
    }

    public static boolean empty(ItemStack stack) {
        return stack == null
                || stack.stackSize <= 0
                || stack.getItem() == null
                || stack.getItem() == Item.getItemFromBlock(Blocks.air);
    }

    public static ItemStack copy(ItemStack stack) {
        return empty(stack) ? EMPTY : stack.copy();
    }

    public static boolean is(ItemStack stack, Item item) {
        return !empty(stack) && stack.getItem() == item;
    }

    public static boolean is(ItemStack stack, Block block) {
        return is(stack, Item.getItemFromBlock(block));
    }

    public static boolean matches(ItemStack a, ItemStack b) {
        return empty(a) ? empty(b) : !empty(b) && ItemStack.areItemStacksEqual(a, b);
    }

    public static boolean same(ItemStack a, ItemStack b) {
        return empty(a)
                ? empty(b)
                : !empty(b) && a.isItemEqual(b) && ItemStack.areItemStackTagsEqual(a, b);
    }

    public static ItemStack carried(Container menu) {
        return Minecraft.getMinecraft().thePlayer.inventory.getItemStack();
    }
}
