package com.blanoir.moons.client.module.world.scaffold;

import java.util.ArrayDeque;
import java.util.List;

/** Bounded, chronological history; exported snapshots cannot change under the writer. */
final class ScaffoldTraceBuffer {
    private final int capacity;
    private final ArrayDeque<String> entries = new ArrayDeque<>();
    private long discarded;

    ScaffoldTraceBuffer(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("capacity");
        this.capacity = capacity;
    }

    void add(String entry) {
        if (entries.size() == capacity) {
            entries.removeFirst();
            discarded++;
        }
        entries.addLast(entry);
    }

    List<String> snapshot() {
        return List.copyOf(entries);
    }

    long discarded() {
        return discarded;
    }

    void clear() {
        entries.clear();
        discarded = 0;
    }
}
