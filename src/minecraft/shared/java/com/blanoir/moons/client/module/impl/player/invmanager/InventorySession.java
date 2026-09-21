package com.blanoir.moons.client.module.impl.player.invmanager;

import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/** Manual edits stay protected until this inventory screen closes. */
public final class InventorySession {
    private final BitSet manualSlots = new BitSet(41);
    private InventorySnapshot manualBefore;
    private long manualUntil;

    private record Attempt(InventoryAction action, long at) {}

    private final List<Attempt> attempts = new ArrayList<>();

    public void manualInput(InventorySnapshot before, long now, int pauseMs) {
        observe(before, now);
        manualBefore = before;
        manualUntil = now + Math.max(50, pauseMs) * 1_000_000L;
    }

    public void observe(InventorySnapshot current, long now) {
        if (manualBefore == null) return;
        for (int i = 0; i < 41; i++)
            if (!ItemStack.matches(manualBefore.item(i), current.item(i))) manualSlots.set(i);
        manualBefore = now < manualUntil ? current : null;
    }

    public boolean paused(long now) {
        return manualBefore != null || now < manualUntil;
    }

    public BitSet protectedSlots() {
        return (BitSet) manualSlots.clone();
    }

    public boolean reserve(InventoryAction action) {
        return reserve(action, System.nanoTime());
    }

    public boolean reserve(InventoryAction action, long now) {
        // Bound retries over time, not over the lifetime of an open inventory screen.
        attempts.removeIf(attempt -> now - attempt.at() >= 5_000_000_000L);
        long previous =
                attempts.stream()
                        .map(Attempt::action)
                        .filter(
                                attempt ->
                                        attempt.source() == action.source()
                                                && attempt.target() == action.target()
                                                && ItemStack.matches(
                                                        attempt.beforeSource(),
                                                        action.beforeSource())
                                                && ItemStack.matches(
                                                        attempt.beforeTarget(),
                                                        action.beforeTarget()))
                        .count();
        if (previous >= 2 || attempts.size() >= 100) return false;
        attempts.add(new Attempt(action, now));
        return true;
    }

    public void retry() {
        attempts.clear();
    }
}
