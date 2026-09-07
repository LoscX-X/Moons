package com.blanoir.moons.client.utils.player;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.function.IntPredicate;
import java.util.function.Predicate;

/** Read-only hotbar searches; selection order and slot ownership remain with callers. */
public final class HotbarQueries {
    private HotbarQueries() {}

    public static int firstItem(Inventory inventory, Item item) {
        return firstMatch(inventory, stack -> stack.is(item));
    }

    public static int firstMatch(Inventory inventory, Predicate<ItemStack> matches) {
        return firstSlot(slot -> matches.test(inventory.getItem(slot)));
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
