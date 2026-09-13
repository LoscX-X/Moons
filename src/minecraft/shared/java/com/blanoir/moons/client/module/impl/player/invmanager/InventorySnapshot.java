package com.blanoir.moons.client.module.impl.player.invmanager;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Inventory indices and menu indices are different; derive the mapping from actual slots. */
public record InventorySnapshot(List<ItemStack> items, int[] menuSlots) {
    public static InventorySnapshot capture(AbstractContainerMenu menu, Inventory inventory) {
        var items = new ArrayList<ItemStack>(41);
        for (int i = 0; i < 41; i++) items.add(ItemStack.EMPTY);
        int[] slots = new int[41];
        Arrays.fill(slots, -1);
        for (int i = 0; i < menu.slots.size(); i++) {
            var slot = menu.slots.get(i);
            int index = slot.getContainerSlot();
            if (slot.container == inventory && index >= 0 && index < 41) {
                items.set(index, slot.getItem().copy());
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
