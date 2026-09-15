package com.blanoir.moons.client.module.impl.network.backtrack;

import com.blanoir.moons.client.management.network.LagUtils;

import java.util.function.Consumer;

/** Client-thread FIFO with bounded storage and no second replay throttle. */
final class BacktrackPacketQueue<T> {
    private final LagUtils<T> entries;
    private int delayMillis;
    private long drainStarted = -1;
    private long playbackTime;

    BacktrackPacketQueue() {
        this(256);
    }

    BacktrackPacketQueue(int capacity) {
        entries = new LagUtils<>(capacity);
    }

    void start(int delayMillis) {
        if (!entries.isEmpty()) throw new IllegalStateException("Previous history is still queued");
        this.delayMillis = Math.max(0, delayMillis);
        drainStarted = -1;
        playbackTime = 0;
    }

    boolean offer(T value, long now) {
        return entries.offer(value, now, delayMillis);
    }

    /** Idempotent: repeated exits cannot restart or extend catch-up. */
    void drain(long now) {
        if (drainStarted < 0) drainStarted = now;
    }

    boolean draining() {
        return drainStarted >= 0;
    }

    boolean drained(long now) {
        return draining() && now - drainStarted >= delayMillis * 2L && entries.isEmpty();
    }

    void releaseDue(long now, Consumer<T> replay) {
        long catchUp = draining() ? Math.min(delayMillis, Math.max(0, now - drainStarted) / 2) : 0;
        pump(Math.max(playbackTime, now + catchUp), replay);
    }

    private void pump(long deadline, Consumer<T> replay) {
        playbackTime = deadline;
        T value;
        while ((value = entries.pollDue(deadline)) != null) replay.accept(value);
    }

    /** Context boundaries use the same FIFO consumer, advancing its clock to the end. */
    void releaseAll(Consumer<T> replay) {
        pump(Long.MAX_VALUE, replay);
    }

    void clear() {
        entries.clear();
        drainStarted = -1;
        playbackTime = 0;
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
