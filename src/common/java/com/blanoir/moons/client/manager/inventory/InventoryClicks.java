package com.blanoir.moons.client.manager.inventory;

import com.blanoir.moons.client.event.EventBus;
import com.blanoir.moons.client.event.network.PacketThread;
import com.blanoir.moons.client.utils.inventory.LegacyItems;

import net.minecraft.client.Minecraft;
import net.minecraft.inventory.Container;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.client.C0EPacketClickWindow;

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
                    Minecraft client = Minecraft.getMinecraft();
                    if (!executing
                            && event.thread() == PacketThread.CLIENT
                            && event.packet() instanceof C0EPacketClickWindow
                            && client.getNetHandler() != null
                            && event.connection() == client.getNetHandler().getNetworkManager()) {
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
            Minecraft client, Container menu, int slotIndex, Object requester) {
        if (client.thePlayer == null
                || client.playerController == null
                || busyExcept(requester)
                || client.thePlayer.openContainer != menu
                || !LegacyItems.empty(LegacyItems.carried(menu))
                || slotIndex < 0
                || slotIndex >= menu.inventorySlots.size()) return false;
        var slot = menu.getSlot(slotIndex);
        if (!slot.canBeHovered() || slot.getHasStack()) return false;
        executing = true;
        try {
            client.playerController.windowClick(menu.windowId, slotIndex, 0, 1, client.thePlayer);
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
            Container menu,
            Object requester,
            int slotIndex,
            ItemStack expectedSlot,
            ItemStack expectedCursor) {
        if (owner != requester
                || client.thePlayer == null
                || client.playerController == null
                || client.thePlayer.openContainer != menu
                || client.thePlayer.inventoryContainer != menu
                || slotIndex < 0
                || slotIndex >= menu.inventorySlots.size()) return false;
        var slot = menu.inventorySlots.get(slotIndex);
        if (!LegacyItems.matches(slot.getStack(), expectedSlot)
                || !LegacyItems.matches(LegacyItems.carried(menu), expectedCursor)
                || !LegacyItems.empty(expectedSlot) && !slot.canTakeStack(client.thePlayer)
                || !LegacyItems.empty(expectedCursor) && !slot.isItemValid(expectedCursor))
            return false;
        executing = true;
        try {
            client.playerController.windowClick(menu.windowId, slotIndex, 0, 0, client.thePlayer);
        } finally {
            executing = false;
            activityVersion++;
        }
        return LegacyItems.matches(slot.getStack(), expectedCursor)
                && LegacyItems.matches(LegacyItems.carried(menu), expectedSlot);
    }

    /** Local prediction is checked here; it is not a server acknowledgement. */
    public static boolean swap(
            Minecraft client,
            Container menu,
            int sourceMenuSlot,
            int targetMenuSlot,
            int hotbarButton,
            ItemStack beforeSource,
            ItemStack beforeTarget) {
        if (owner != null
                || client.thePlayer == null
                || client.playerController == null
                || client.thePlayer.openContainer != menu
                || client.thePlayer.inventoryContainer != menu
                || !LegacyItems.empty(LegacyItems.carried(menu))
                || sourceMenuSlot < 0
                || targetMenuSlot < 0
                || sourceMenuSlot >= menu.inventorySlots.size()
                || targetMenuSlot >= menu.inventorySlots.size()
                || sourceMenuSlot == targetMenuSlot
                || (hotbarButton < 0 || hotbarButton > 8)) return false;
        var source = menu.inventorySlots.get(sourceMenuSlot);
        var target = menu.inventorySlots.get(targetMenuSlot);
        if (source.inventory != client.thePlayer.inventory
                || target.inventory != source.inventory
                || LegacyItems.slotIndex(target) != hotbarButton
                || !source.canTakeStack(client.thePlayer)
                || !target.canTakeStack(client.thePlayer)
                || !target.isItemValid(beforeSource)
                || !LegacyItems.empty(beforeTarget) && !source.isItemValid(beforeTarget)
                || !LegacyItems.matches(source.getStack(), beforeSource)
                || !LegacyItems.matches(target.getStack(), beforeTarget)) return false;
        executing = true;
        try {
            client.playerController.windowClick(
                    menu.windowId, sourceMenuSlot, hotbarButton, 2, client.thePlayer);
        } finally {
            executing = false;
            activityVersion++;
        }
        return LegacyItems.empty(LegacyItems.carried(menu))
                && LegacyItems.matches(source.getStack(), beforeTarget)
                && LegacyItems.matches(target.getStack(), beforeSource);
    }

    public static boolean hasEmptyStorage(Minecraft client, Container menu, ItemStack item) {
        return menu.inventorySlots.stream()
                .anyMatch(
                        slot ->
                                slot.inventory == client.thePlayer.inventory
                                        && LegacyItems.slotIndex(slot) >= 0
                                        && LegacyItems.slotIndex(slot) < 36
                                        && LegacyItems.empty(slot.getStack())
                                        && slot.isItemValid(item)
                                        && slot.getItemStackLimit(item) >= item.stackSize);
    }

    /** A single shift-click never puts an item on the cursor. Replan after each move. */
    public static boolean quickMove(
            Minecraft client, Container menu, int menuSlot, ItemStack expected) {
        if (owner != null
                || client.thePlayer == null
                || client.playerController == null
                || client.thePlayer.openContainer != menu
                || client.thePlayer.inventoryContainer != menu
                || !LegacyItems.empty(LegacyItems.carried(menu))
                || LegacyItems.empty(expected)
                || menuSlot < 0
                || menuSlot >= menu.inventorySlots.size()) return false;
        var slot = menu.getSlot(menuSlot);
        if (slot.inventory != client.thePlayer.inventory
                || !slot.canTakeStack(client.thePlayer)
                || !LegacyItems.matches(slot.getStack(), expected)) return false;
        executing = true;
        try {
            client.playerController.windowClick(menu.windowId, menuSlot, 0, 1, client.thePlayer);
        } finally {
            executing = false;
            activityVersion++;
        }
        return LegacyItems.empty(slot.getStack()) && LegacyItems.empty(LegacyItems.carried(menu));
    }

    /** Drop one complete player-inventory stack only when it still matches the planned item. */
    public static boolean dropStack(
            Minecraft client, Container menu, int menuSlot, ItemStack expected) {
        if (owner != null
                || client.thePlayer == null
                || client.playerController == null
                || client.thePlayer.openContainer != menu
                || client.thePlayer.inventoryContainer != menu
                || !LegacyItems.empty(LegacyItems.carried(menu))
                || LegacyItems.empty(expected)
                || menuSlot < 0
                || menuSlot >= menu.inventorySlots.size()) return false;
        var slot = menu.inventorySlots.get(menuSlot);
        if (slot.inventory != client.thePlayer.inventory
                || LegacyItems.slotIndex(slot) < 0
                || LegacyItems.slotIndex(slot) >= 36
                || !slot.canTakeStack(client.thePlayer)
                || !LegacyItems.matches(slot.getStack(), expected)) return false;
        executing = true;
        try {
            client.playerController.windowClick(menu.windowId, menuSlot, 1, 4, client.thePlayer);
        } finally {
            executing = false;
            activityVersion++;
        }
        return LegacyItems.empty(slot.getStack()) && LegacyItems.empty(LegacyItems.carried(menu));
    }
}
