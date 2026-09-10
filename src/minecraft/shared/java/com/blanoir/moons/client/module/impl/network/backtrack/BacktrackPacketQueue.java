package com.blanoir.moons.client.module.impl.network.backtrack;

import com.blanoir.moons.client.management.network.LagUtils;

import java.util.function.Consumer;

/** Client-thread FIFO with bounded storage and no second replay throttle. */
final class BacktrackPacketQueue<T> {
    private final LagUtils<T> entries;

    BacktrackPacketQueue() {
        this(256);
    }

    BacktrackPacketQueue(int capacity) {
        entries = new LagUtils<>(capacity);
    }

    boolean offer(T value, long now, int delayMillis) {
        return entries.offer(value, now, delayMillis);
    }

    void releaseDue(long now, Consumer<T> replay) {
        T value;
        while ((value = entries.pollDue(now)) != null) replay.accept(value);
    }

    void releaseAll(Consumer<T> replay) {
        T value;
        while ((value = entries.poll()) != null) replay.accept(value);
    }

    void clear() {
        entries.clear();
    }

    boolean isEmpty() {
        return entries.isEmpty();
    }

    int size() {
        return entries.size();
    }

    long age(long now) {
        return entries.age(now);
    }
}
