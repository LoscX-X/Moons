package com.blanoir.moons.client.utils.inventory;

import com.blanoir.moons.client.event.EventBus;
import com.blanoir.moons.client.event.network.PacketThread;

import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;

/** Native, cursor-free inventory operations plus observation of competing inventory activity. */
public final class InventoryClicks {
    private static boolean initialized;
    private static boolean executing;
    private static long foreignClickAt = Long.MIN_VALUE;
    private static Object owner;
    private static boolean preemptRequested;
    private static long activityVersion;

    private InventoryClicks() {}

    public static void init() {
        if (initialized) return;
        initialized = true;
        EventBus.CLIENT_CONTEXT_CHANGED.register(
                "InventoryClicks.context",
                event -> {
                    foreignClickAt = Long.MIN_VALUE;
                    owner = null;
                    preemptRequested = false;
                });
        EventBus.PACKET_SEND_POST.register(
                "InventoryClicks.observe",
                event -> {
                    Minecraft client = Minecraft.getInstance();
                    if (!executing
                            && event.thread() == PacketThread.CLIENT
                            && event.packet() instanceof ServerboundContainerClickPacket
                            && client.getConnection() != null
                            && event.connection() == client.getConnection().getConnection()) {
                        foreignClickAt = System.nanoTime();
                        activityVersion++;
                    }
                });
    }

    public static long activityVersion() {
        return activityVersion;
    }

    /** A harmless automated misclick. It must never be mistaken for manual inventory input. */
    public static boolean clickEmpty(
            Minecraft client, AbstractContainerMenu menu, int slotIndex, Object requester) {
        if (client.player == null
                || client.gameMode == null
                || busyExcept(requester)
                || client.player.containerMenu != menu
                || !menu.getCarried().isEmpty()
                || slotIndex < 0
                || slotIndex >= menu.slots.size()) return false;
        var slot = menu.getSlot(slotIndex);
        if (!slot.isActive() || slot.hasItem()) return false;
        executing = true;
        try {
            client.gameMode.handleContainerInput(
                    menu.containerId, slotIndex, 0, ContainerInput.QUICK_MOVE, client.player);
        } finally {
            executing = false;
            activityVersion++;
        }
        return true;
    }

    public static boolean recentlyBusy(long now) {
        return foreignClickAt != Long.MIN_VALUE && now - foreignClickAt < 200_000_000L;
    }

    public static boolean busyExcept(Object requester) {
        return owner != null && owner != requester;
    }

    public static boolean acquire(Object requester) {
        if (busyExcept(requester)) return false;
        owner = requester;
        return true;
    }

    public static void release(Object requester) {
        if (owner == requester) {
            owner = null;
            preemptRequested = false;
        }
    }

    public static void requestPreemption() {
        preemptRequested = owner != null;
    }

    public static boolean preemptRequested() {
        return preemptRequested;
    }

    /** A cursor operation must belong to the active transaction and match its expected state. */
    public static boolean pickup(
            Minecraft client,
            AbstractContainerMenu menu,
            Object requester,
            int slotIndex,
            ItemStack expectedSlot,
            ItemStack expectedCursor) {
        if (owner != requester
                || client.player == null
                || client.gameMode == null
                || client.player.containerMenu != menu
                || client.player.inventoryMenu != menu
                || slotIndex < 0
                || slotIndex >= menu.slots.size()) return false;
        var slot = menu.slots.get(slotIndex);
        if (!ItemStack.matches(slot.getItem(), expectedSlot)
                || !ItemStack.matches(menu.getCarried(), expectedCursor)
                || !expectedSlot.isEmpty() && !slot.mayPickup(client.player)
                || !expectedCursor.isEmpty() && !slot.mayPlace(expectedCursor)) return false;
        executing = true;
        try {
            client.gameMode.handleContainerInput(
                    menu.containerId, slotIndex, 0, ContainerInput.PICKUP, client.player);
        } finally {
            executing = false;
            activityVersion++;
        }
        return ItemStack.matches(slot.getItem(), expectedCursor)
                && ItemStack.matches(menu.getCarried(), expectedSlot);
    }

    /** Local prediction is checked here; it is not a server acknowledgement. */
    public static boolean swap(
            Minecraft client,
            AbstractContainerMenu menu,
            int sourceMenuSlot,
            int targetMenuSlot,
            int hotbarButton,
            ItemStack beforeSource,
            ItemStack beforeTarget) {
        if (owner != null
                || client.player == null
                || client.gameMode == null
                || client.player.containerMenu != menu
                || client.player.inventoryMenu != menu
                || !menu.getCarried().isEmpty()
                || sourceMenuSlot < 0
                || targetMenuSlot < 0
                || sourceMenuSlot >= menu.slots.size()
                || targetMenuSlot >= menu.slots.size()
                || sourceMenuSlot == targetMenuSlot
                || hotbarButton != 40 && (hotbarButton < 0 || hotbarButton > 8)) return false;
        var source = menu.slots.get(sourceMenuSlot);
        var target = menu.slots.get(targetMenuSlot);
        if (source.container != client.player.getInventory()
                || target.container != source.container
                || target.getContainerSlot() != hotbarButton
                || !source.mayPickup(client.player)
                || !target.mayPickup(client.player)
                || !target.mayPlace(beforeSource)
                || !beforeTarget.isEmpty() && !source.mayPlace(beforeTarget)
                || !ItemStack.matches(source.getItem(), beforeSource)
                || !ItemStack.matches(target.getItem(), beforeTarget)) return false;
        executing = true;
        try {
            client.gameMode.handleContainerInput(
                    menu.containerId,
                    sourceMenuSlot,
                    hotbarButton,
                    ContainerInput.SWAP,
                    client.player);
        } finally {
            executing = false;
            activityVersion++;
        }
        return menu.getCarried().isEmpty()
                && ItemStack.matches(source.getItem(), beforeTarget)
                && ItemStack.matches(target.getItem(), beforeSource);
    }

    public static boolean hasEmptyStorage(
            Minecraft client, AbstractContainerMenu menu, ItemStack item) {
        return menu.slots.stream()
                .anyMatch(
                        slot ->
                                slot.container == client.player.getInventory()
                                        && slot.getContainerSlot() >= 0
                                        && slot.getContainerSlot() < 36
                                        && slot.getItem().isEmpty()
                                        && slot.mayPlace(item)
                                        && slot.getMaxStackSize(item) >= item.getCount());
    }

    /** A single shift-click never puts an item on the cursor. Replan after each move. */
    public static boolean quickMove(
            Minecraft client, AbstractContainerMenu menu, int menuSlot, ItemStack expected) {
        if (owner != null
                || client.player == null
                || client.gameMode == null
                || client.player.containerMenu != menu
                || client.player.inventoryMenu != menu
                || !menu.getCarried().isEmpty()
                || expected.isEmpty()
                || menuSlot < 0
                || menuSlot >= menu.slots.size()) return false;
        var slot = menu.getSlot(menuSlot);
        if (slot.container != client.player.getInventory()
                || !slot.mayPickup(client.player)
                || !ItemStack.matches(slot.getItem(), expected)) return false;
        executing = true;
        try {
            client.gameMode.handleContainerInput(
                    menu.containerId, menuSlot, 0, ContainerInput.QUICK_MOVE, client.player);
        } finally {
            executing = false;
            activityVersion++;
        }
        return slot.getItem().isEmpty() && menu.getCarried().isEmpty();
    }

    /** Drop one complete player-inventory stack only when it still matches the planned item. */
    public static boolean dropStack(
            Minecraft client, AbstractContainerMenu menu, int menuSlot, ItemStack expected) {
        if (owner != null
                || client.player == null
                || client.gameMode == null
                || client.player.containerMenu != menu
                || client.player.inventoryMenu != menu
                || !menu.getCarried().isEmpty()
                || expected.isEmpty()
                || menuSlot < 0
                || menuSlot >= menu.slots.size()) return false;
        var slot = menu.slots.get(menuSlot);
        if (slot.container != client.player.getInventory()
                || slot.getContainerSlot() < 0
                || slot.getContainerSlot() >= 36
                || !slot.mayPickup(client.player)
                || !ItemStack.matches(slot.getItem(), expected)) return false;
        executing = true;
        try {
            client.gameMode.handleContainerInput(
                    menu.containerId, menuSlot, 1, ContainerInput.THROW, client.player);
        } finally {
            executing = false;
            activityVersion++;
        }
        return slot.getItem().isEmpty() && menu.getCarried().isEmpty();
    }
}
