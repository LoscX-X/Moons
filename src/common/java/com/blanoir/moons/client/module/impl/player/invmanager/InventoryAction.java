package com.blanoir.moons.client.module.impl.player.invmanager;

import net.minecraft.world.item.ItemStack;

/** A desired exchange; equipment exchanges are executed as a checked cursor transaction. */
public record InventoryAction(
        int source, int target, ItemStack beforeSource, ItemStack beforeTarget, String reason) {
    public boolean equipment() {
        return target >= 36 && target < 40;
    }
}
