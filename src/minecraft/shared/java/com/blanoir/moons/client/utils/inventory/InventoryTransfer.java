package com.blanoir.moons.client.utils.inventory;

import com.blanoir.moons.client.access.MinecraftClientAccess;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

/** Two/three checked PICKUP clicks. Cancellation returns only a cursor we can still identify. */
public final class InventoryTransfer {
    private LocalPlayer player;
    private AbstractContainerMenu menu;
    private int source, target, step;
    private ItemStack incoming = ItemStack.EMPTY, outgoing = ItemStack.EMPTY;
    private boolean cancelled;
    private long startedAt;
    private String result = "";

    public boolean active() {
        return step != 0;
    }

    public String result() {
        return result;
    }

    public void cancel() {
        cancelled = true;
    }

    public boolean begin(
            Minecraft client, int source, int target, ItemStack incoming, ItemStack outgoing) {
        if (active()
                || incoming.getCount() != 1
                || outgoing.getCount() > 1
                || !client.player.inventoryMenu.getCarried().isEmpty()
                || !InventoryClicks.acquire(this)) return false;
        this.player = client.player;
        this.menu = player.inventoryMenu;
        this.source = source;
        this.target = target;
        this.incoming = incoming.copy();
        this.outgoing = outgoing.copy();
        cancelled = false;
        result = "Changing armor";
        startedAt = System.nanoTime();
        step = 1;
        return true;
    }

    public void abandon() {
        step = 0;
        player = null;
        menu = null;
        incoming = outgoing = ItemStack.EMPTY;
        InventoryClicks.release(this);
    }

    /** Called before the normal nonempty-cursor pause. At most one native click per call. */
    public void tick(Minecraft client, boolean allowClick) {
        if (!active()) return;
        if (client.player != player
                || client.player == null
                || client.player.inventoryMenu != menu) {
            finish("Inventory context changed");
            return;
        }
        if (client.player.containerMenu != menu
                || !(MinecraftClientAccess.screen(client) instanceof InventoryScreen)
                || player.isDeadOrDying()
                || player.isSpectator()) {
            finish(
                    menu.getCarried().isEmpty()
                            ? "Armor action cancelled"
                            : "Finish the held item manually");
            return;
        }
        cancelled |=
                InventoryClicks.preemptRequested()
                        || System.nanoTime() - startedAt > 10_000_000_000L;
        if (!allowClick) return;
        ItemStack expectedSource = step == 1 ? incoming : ItemStack.EMPTY;
        ItemStack expectedTarget = step < 3 ? outgoing : incoming;
        ItemStack expectedCursor = step == 1 ? ItemStack.EMPTY : step == 2 ? incoming : outgoing;
        if (source < 0
                || target < 0
                || source >= menu.slots.size()
                || target >= menu.slots.size()
                || !ItemStack.matches(menu.getSlot(source).getItem(), expectedSource)
                || !ItemStack.matches(menu.getSlot(target).getItem(), expectedTarget)
                || !ItemStack.matches(menu.getCarried(), expectedCursor)) {
            finish("Inventory changed · finish any held item manually");
            return;
        }
        if (cancelled && step == 1) {
            finish("Armor action cancelled");
            return;
        }
        int click = cancelled || step != 2 ? source : target;
        if (!InventoryClicks.pickup(
                client, menu, this, click, menu.getSlot(click).getItem().copy(), expectedCursor)) {
            finish("Slots did not settle · finish any held item manually");
            return;
        }
        if (cancelled || step == 3 || step == 2 && outgoing.isEmpty()) {
            finish(cancelled ? "Armor action cancelled" : "Armor equipped");
        } else step++;
    }

    private void finish(String message) {
        result = message;
        abandon();
    }
}
