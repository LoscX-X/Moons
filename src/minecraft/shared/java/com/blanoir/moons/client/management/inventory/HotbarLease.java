package com.blanoir.moons.client.management.inventory;

import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;

/** Arbitrates temporary hotbar ownership and restores the user's latest slot. */
public final class HotbarLease {
    private static HotbarLease holder;

    private final String owner;
    private final int priority;
    private int restoreSlot = -1;
    private int leasedSlot = -1;
    private HotbarLease previous;
    private boolean cancelled;

    public HotbarLease(String owner, int priority) {
        this.owner = owner;
        this.priority = priority;
    }

    public synchronized boolean acquire(Minecraft client, int slot) {
        var currentPlayer = client == null ? null : client.player;
        if (client == null || currentPlayer == null || slot < 0 || slot > 8) return false;
        synchronized (HotbarLease.class) {
            if (holder != null && holder != this && holder.priority >= priority) return false;
            if (holder != null && holder != this) previous = holder;
            holder = this;
        }
        cancelled = false;
        if (restoreSlot < 0) restoreSlot = currentPlayer.getInventory().getSelectedSlot();
        leasedSlot = slot;
        select(client, slot);
        return true;
    }

    /** Called by input hooks when the user selects a slot while a lease is active. */
    public synchronized void userSelected(int slot) {
        if (slot >= 0 && slot <= 8) restoreSlot = slot;
    }

    public synchronized void userScrolled(int offset) {
        if (restoreSlot >= 0 && offset != 0) restoreSlot = Math.floorMod(restoreSlot - offset, 9);
    }

    public synchronized ItemStack userStack(Minecraft client, ItemStack fallback) {
        var currentPlayer = client == null ? null : client.player;
        return client != null && currentPlayer != null && restoreSlot >= 0 && restoreSlot <= 8
                ? currentPlayer.getInventory().getItem(restoreSlot)
                : fallback;
    }

    public synchronized boolean active() {
        return holder == this;
    }

    public synchronized int leasedSlot() {
        return leasedSlot;
    }

    public synchronized String owner() {
        return owner;
    }

    public synchronized void release(Minecraft client) {
        var currentPlayer = client == null ? null : client.player;
        if (holder == this && client != null && currentPlayer != null && restoreSlot >= 0) {
            int current = currentPlayer.getInventory().getSelectedSlot();
            if (current == leasedSlot) select(client, restoreSlot);
        }
        finish();
    }

    public synchronized void abandon() {
        finish();
    }

    private void finish() {
        boolean owned;
        synchronized (HotbarLease.class) {
            owned = holder == this;
            if (owned) {
                HotbarLease next = previous;
                while (next != null && next.cancelled) next = next.previous;
                holder = next;
            } else {
                cancelled = true;
            }
        }
        if (owned) previous = null;
        restoreSlot = -1;
        leasedSlot = -1;
    }

    private static void select(Minecraft client, int slot) {
        if (client.player.getInventory().getSelectedSlot() == slot) return;
        client.player.getInventory().setSelectedSlot(slot);
    }
}
