package com.blanoir.moons.client.module.impl.network.backtrack;

import java.util.ArrayDeque;
import java.util.function.Consumer;

/** Client-thread FIFO with bounded storage and no second replay throttle. */
final class BacktrackPacketQueue<T> {
    private static final int CAPACITY = 256;
    private final ArrayDeque<Entry<T>> entries = new ArrayDeque<>();

    boolean offer(T value, long now, int delayMillis) {
        if (entries.size() >= CAPACITY) return false;
        Entry<T> last = entries.peekLast();
        entries.addLast(
                new Entry<>(
                        value,
                        now,
                        BacktrackTiming.deadline(
                                now, delayMillis, last == null ? 0 : last.releaseAt())));
        return true;
    }

    void releaseDue(long now, Consumer<T> replay) {
        while (!entries.isEmpty() && BacktrackTiming.due(now, entries.getFirst().releaseAt())) {
            replay.accept(entries.removeFirst().value());
        }
    }

    void releaseAll(Consumer<T> replay) {
        while (!entries.isEmpty()) replay.accept(entries.removeFirst().value());
    }

    void clear() {
        entries.clear();
    }

    boolean isEmpty() {
        return entries.isEmpty();
    }

    long age(long now) {
        return entries.isEmpty() ? 0 : Math.max(0, now - entries.getFirst().arrival());
    }

    private record Entry<T>(T value, long arrival, long releaseAt) {}
}
