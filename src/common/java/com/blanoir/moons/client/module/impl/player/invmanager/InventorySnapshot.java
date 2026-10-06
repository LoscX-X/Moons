package com.blanoir.moons.client.module.impl.player.invmanager;

import com.blanoir.moons.client.utils.inventory.LegacyItems;

import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.item.ItemStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** InventoryPlayer indices and menu indices are different; derive the mapping from actual slots. */
public record InventorySnapshot(List<ItemStack> items, int[] menuSlots) {
    public static InventorySnapshot capture(Container menu, InventoryPlayer inventory) {
        var items = new ArrayList<ItemStack>(40);
        for (int i = 0; i < 40; i++) items.add(LegacyItems.EMPTY);
        int[] slots = new int[40];
        Arrays.fill(slots, -1);
        for (int i = 0; i < menu.inventorySlots.size(); i++) {
            var slot = menu.inventorySlots.get(i);
            int index = LegacyItems.slotIndex(slot);
            if (slot.inventory == inventory && index >= 0 && index < 40) {
                items.set(index, LegacyItems.copy(slot.getStack()));
                slots[index] = i;
            }
        }
        return new InventorySnapshot(List.copyOf(items), slots);
    }

    public ItemStack item(int index) {
        return items.get(index);
    }

    public int menuSlot(int index) {
        return menuSlots[index];
    }
}
