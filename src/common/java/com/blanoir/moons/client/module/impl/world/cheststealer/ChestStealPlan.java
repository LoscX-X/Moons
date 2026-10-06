package com.blanoir.moons.client.module.impl.world.cheststealer;

import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** Cursor-free storage batch. Hotbar shuttles always land in an empty player-owned slot. */
public final class ChestStealPlan {
    public record Swap(
            int slot,
            int hotbarSlot,
            int button,
            ItemStack beforeSlot,
            ItemStack beforeHotbar,
            boolean fromContainer) {}

    private ChestStealPlan() {}

    public static List<Swap> build(
            List<ItemStack> stacks, int[] inventorySlots, List<Integer> sources) {
        if (inventorySlots.length != 36) return List.of();
        var unique = new java.util.HashSet<Integer>();
        for (int index : inventorySlots)
            if (index < 0 || index >= stacks.size() || !unique.add(index)) return List.of();
        var working = new ArrayList<>(stacks);
        var result = new ArrayList<Swap>();
        int transferred = 0;
        for (int source : sources) {
            if (transferred >= 36) break;
            if (source < 0
                    || source >= stacks.size()
                    || unique.contains(source)
                    || working.get(source).isEmpty()) continue;
            int button = empty(working, inventorySlots, 0, 9);
            if (button < 0) {
                int storage = empty(working, inventorySlots, 9, 36);
                if (storage < 0) break;
                button = 0;
                append(
                        result,
                        working,
                        inventorySlots[storage],
                        inventorySlots[button],
                        button,
                        false);
            }
            append(result, working, source, inventorySlots[button], button, true);
            transferred++;
        }
        return List.copyOf(result);
    }

    private static int empty(List<ItemStack> stacks, int[] slots, int start, int end) {
        for (int index = start; index < end; index++)
            if (stacks.get(slots[index]).isEmpty()) return index;
        return -1;
    }

    private static void append(
            List<Swap> actions,
            List<ItemStack> working,
            int slot,
            int hotbar,
            int button,
            boolean fromContainer) {
        ItemStack source = working.get(slot), target = working.get(hotbar);
        actions.add(new Swap(slot, hotbar, button, source.copy(), target.copy(), fromContainer));
        working.set(slot, target);
        working.set(hotbar, source);
    }
}
