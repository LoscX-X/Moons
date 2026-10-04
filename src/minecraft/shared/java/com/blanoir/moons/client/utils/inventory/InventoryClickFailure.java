package com.blanoir.moons.client.utils.inventory;

import com.blanoir.moons.client.config.settings.IntSetting;
import com.blanoir.moons.client.module.framework.ModuleRegistry;

import net.minecraft.client.Minecraft;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.IInventory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/** Optional harmless misclicks. Each miss is followed by a normal attempt, even at 100%. */
public final class InventoryClickFailure {
    private final IntSetting rate;
    private final Recovery recovery = new Recovery();
    private Container context;

    public InventoryClickFailure(String module) {
        rate =
                new IntSetting.Builder()
                        .name(module + ".failureRate")
                        .defaultValue(0)
                        .range(0, 100)
                        .build();
    }

    public ModuleRegistry.Setting setting() {
        return rate.describe(
                "failure_rate",
                "Failure rate (%)",
                1,
                (client, value) -> {
                    rate.set(value);
                    reset();
                    return 1;
                });
    }

    public void reset() {
        context = null;
        recovery.reset();
    }

    /** True consumes this action's turn only; the caller applies its usual delay and replans. */
    public boolean beforeClick(Minecraft client, Container menu, int intendedSlot, Object owner) {
        if (rate.get() == 0) {
            reset();
            return false;
        }
        if (client.thePlayer == null
                || client.playerController == null
                || client.thePlayer.openContainer != menu
                || !LegacyItems.empty(LegacyItems.carried(menu))
                || InventoryClicks.busyExcept(owner)
                || intendedSlot < 0
                || intendedSlot >= menu.inventorySlots.size()) return false;
        if (context != menu) {
            reset();
            context = menu;
        }
        var candidates = emptySlots(menu, intendedSlot, client.thePlayer.inventory);
        if (candidates.isEmpty()) return false;
        if (!recovery.miss(rate.get(), ThreadLocalRandom.current().nextInt(100))) return false;
        int slot = candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
        // Empty QUICK_MOVE is a real slot click with no item or cursor mutation, including
        // creative.
        if (InventoryClicks.clickEmpty(client, menu, slot, owner)) return true;
        recovery.reset();
        return false;
    }

    static List<Integer> emptySlots(Container menu, int intendedSlot, IInventory inventory) {
        var source = menu.getSlot(intendedSlot).inventory;
        var result = new ArrayList<Integer>();
        for (int index = 0; index < menu.inventorySlots.size(); index++) {
            var slot = menu.getSlot(index);
            boolean storage =
                    slot.inventory == inventory
                            ? LegacyItems.slotIndex(slot) >= 0 && LegacyItems.slotIndex(slot) < 36
                            : slot.inventory == source;
            if (index != intendedSlot && storage && slot.canBeHovered() && !slot.getHasStack())
                result.add(index);
        }
        return result;
    }

    static final class Recovery {
        private boolean resume;

        boolean miss(int percent, int roll) {
            if (percent <= 0 || resume) {
                resume = false;
                return false;
            }
            resume = roll < percent;
            return resume;
        }

        void reset() {
            resume = false;
        }
    }
}
