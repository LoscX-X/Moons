package com.blanoir.moons.client.module.impl.misc.aimdata;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** Five-second bounded pre-roll; only confirmed combat promotes snapshots to the disk queue. */
final class CombatCapture {
    static final long PRE_ROLL_NANOS = 5_000_000_000L;
    private final ArrayDeque<AimGeometry.Sample> history = new ArrayDeque<>();
    private final Map<String, Window> windows = new HashMap<>();
    private final int capacity;
    private long sequence, evicted;

    CombatCapture(int capacity) {
        this.capacity = capacity;
    }

    void retain(Set<String> players, long now) {
        windows.keySet().retainAll(players);
        history.removeIf(sample -> !players.contains(sample.observer().uuid()));
        expire(now);
    }

    void observe(AimGeometry.Sample sample, Consumer<AimGeometry.Sample> sink) {
        expire(sample.appliedNanos());
        if (history.size() >= capacity) {
            history.removeFirst();
            evicted++;
        }
        history.addLast(sample);
        Window window = windows.get(sample.observer().uuid());
        if (window != null && sample.appliedNanos() <= window.until) emit(sample, window, sink);
    }

    void combat(
            String attacker,
            int attackerId,
            String victim,
            int victimId,
            long now,
            long duration,
            Consumer<AimGeometry.Sample> sink) {
        if (attacker.equals(victim)) return;
        expire(now);
        arm(attacker, victimId, now, duration);
        arm(victim, attackerId, now, duration);
        // Promote both players together in observation order; other players stay in memory.
        for (AimGeometry.Sample sample : history) {
            String uuid = sample.observer().uuid();
            if (uuid.equals(attacker) || uuid.equals(victim)) emit(sample, windows.get(uuid), sink);
        }
    }

    private void arm(String uuid, int opponent, long now, long duration) {
        Window window = windows.computeIfAbsent(uuid, ignored -> new Window());
        if (window.lastDamage < 0 || now > window.until) window.id = now;
        window.lastDamage = now;
        window.until = now + duration;
        window.opponent = opponent;
    }

    private void emit(AimGeometry.Sample sample, Window window, Consumer<AimGeometry.Sample> sink) {
        if (sample.sequence() <= window.lastSaved) return;
        sink.accept(
                sample.withCombat(
                        ++sequence,
                        window.id,
                        window.lastDamage,
                        window.until,
                        window.opponent,
                        evicted));
        window.lastSaved = sample.sequence();
    }

    void expire(long now) {
        while (!history.isEmpty() && now - history.getFirst().appliedNanos() > PRE_ROLL_NANOS)
            history.removeFirst();
    }

    private static final class Window {
        long id, until, lastSaved, lastDamage = -1;
        int opponent;
    }
}
