package com.blanoir.moons.client.utils.inventory;

import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;

public final class InventoryFailureVerification {
    public static void main(String[] args) {
        var first = new InventoryClickFailure.Recovery();
        var second = new InventoryClickFailure.Recovery();
        for (int i = 0; i < 200; i++) {
            require(first.miss(100, 99), "100% inserts a miss");
            require(!first.miss(100, 0), "Every miss must allow normal progress");
        }
        require(first.miss(100, 0) && second.miss(100, 0), "Modules do not share recovery state");
        require(!first.miss(0, 0), "Disabling failures cancels pending recovery");
        require(first.miss(100, 0), "Re-enabling starts a fresh sequence");
        first.reset();
        require(first.miss(100, 0), "A new context clears old recovery");
        int count = 0;
        for (int roll = 0; roll < 100; roll++) {
            first.reset();
            if (first.miss(25, roll)) count++;
        }
        require(count == 25, "Configured percent is applied to initial attempts");

        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        Items.STONE
                .builtInRegistryHolder()
                .bindComponents(
                        DataComponentMap.builder().set(DataComponents.MAX_STACK_SIZE, 64).build());
        var chest = new SimpleContainer(3);
        var inventory = new SimpleContainer(41);
        var crafting = new SimpleContainer(1);
        var menu = new FixtureMenu();
        menu.append(new Slot(chest, 0, 0, 0)); // Intended occupied source.
        menu.append(new Slot(chest, 1, 0, 0)); // Safe chest miss.
        menu.append(
                new Slot(chest, 2, 0, 0) { // Disabled slot must be excluded.
                    @Override
                    public boolean isActive() {
                        return false;
                    }
                });
        menu.append(new Slot(inventory, 9, 0, 0)); // Safe player storage miss.
        menu.append(new Slot(inventory, 36, 0, 0)); // Armor is not storage.
        menu.append(new Slot(inventory, 40, 0, 0)); // Offhand is not storage.
        menu.append(new Slot(crafting, 0, 0, 0)); // Never click an unrelated menu area.
        chest.setItem(0, new ItemStack(Items.STONE));
        require(
                InventoryClickFailure.emptySlots(menu, 0, inventory).equals(List.of(1, 3)),
                "Only empty active storage slots can be clicked");
        chest.setItem(1, new ItemStack(Items.STONE));
        inventory.setItem(9, new ItemStack(Items.STONE));
        require(
                InventoryClickFailure.emptySlots(menu, 0, inventory).isEmpty(),
                "Full storage skips failure instead of moving or dropping another item");
        inventory.setItem(9, ItemStack.EMPTY);
        require(
                InventoryClickFailure.emptySlots(menu, 3, inventory).isEmpty(),
                "Never misclick the intended slot or another container");
        System.out.println("MOONS_INVENTORY_FAILURES_VERIFIED");
    }

    private static final class FixtureMenu extends AbstractContainerMenu {
        FixtureMenu() {
            super(null, 0);
        }

        void append(Slot slot) {
            addSlot(slot);
        }

        @Override
        public boolean stillValid(Player player) {
            return true;
        }

        @Override
        public ItemStack quickMoveStack(Player player, int slot) {
            return ItemStack.EMPTY;
        }
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
