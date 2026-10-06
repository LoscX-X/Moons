package com.blanoir.moons.client.utils.player;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

import java.util.function.IntPredicate;
import java.util.function.Predicate;

/** Read-only hotbar searches; selection order and slot ownership remain with callers. */
public final class HotbarQueries {
    private HotbarQueries() {}

    public static int firstItem(InventoryPlayer inventory, Item item) {
        return firstMatch(
                inventory,
                stack -> stack != null && stack.stackSize > 0 && stack.getItem() == item);
    }

    public static int firstItem(Minecraft client, Item item) {
        return firstItem(client.thePlayer.inventory, item);
    }

    public static int firstMatch(InventoryPlayer inventory, Predicate<ItemStack> matches) {
        return firstSlot(slot -> matches.test(inventory.getStackInSlot(slot)));
    }

    /** Visits slots 0 through 8 in order, stopping at the first match. */
    public static int firstSlot(IntPredicate matches) {
        for (int slot = 0; slot < 9; slot++) {
            if (matches.test(slot)) {
                return slot;
            }
        }
        return -1;
    }
}
