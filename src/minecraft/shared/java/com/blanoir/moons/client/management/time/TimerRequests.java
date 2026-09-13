package com.blanoir.moons.client.management.time;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

/** Monotonic, expiring speed requests. Highest priority wins; the latest request breaks ties. */
public final class TimerRequests {
    public static final float MIN_MULTIPLIER = .1F;
    public static final float MAX_MULTIPLIER = 10F;
    private final LongSupplier clock;
    private final Map<Object, Request> requests = new IdentityHashMap<>();
    private long sequence;

    public TimerRequests() {
        this(System::nanoTime);
    }

    public TimerRequests(LongSupplier clock) {
        this.clock = Objects.requireNonNull(clock);
    }

    public synchronized void request(
            Object owner, float multiplier, int priority, long holdMillis) {
        Objects.requireNonNull(owner, "Timer request owner");
        if (!Float.isFinite(multiplier)
                || multiplier < MIN_MULTIPLIER
                || multiplier > MAX_MULTIPLIER)
            throw new IllegalArgumentException("Timer multiplier must be between 0.1 and 10");
        if (holdMillis < 1 || holdMillis > 60_000)
            throw new IllegalArgumentException("Timer hold time must be between 1 and 60000 ms");
        requests.put(
                owner,
                new Request(
                        multiplier,
                        priority,
                        ++sequence,
                        clock.getAsLong(),
                        holdMillis * 1_000_000L));
    }

    public synchronized void release(Object owner) {
        requests.remove(owner);
    }

    public synchronized void reset() {
        requests.clear();
        sequence = 0;
    }

    public synchronized boolean active() {
        prune();
        return !requests.isEmpty();
    }

    public synchronized float multiplier() {
        prune();
        Request selected = null;
        for (Request request : requests.values()) {
            if (selected == null
                    || request.priority > selected.priority
                    || request.priority == selected.priority
                            && request.sequence > selected.sequence) selected = request;
        }
        return selected == null ? 1F : selected.multiplier;
    }

    public synchronized float adjustTickMillis(float vanillaMillis) {
        if (!Float.isFinite(vanillaMillis) || vanillaMillis <= 0) return vanillaMillis;
        float adjusted = vanillaMillis / multiplier();
        return Float.isFinite(adjusted) && adjusted > 0 ? adjusted : vanillaMillis;
    }

    private void prune() {
        long now = clock.getAsLong();
        requests.values().removeIf(request -> now - request.startedNanos >= request.durationNanos);
    }

    private record Request(
            float multiplier, int priority, long sequence, long startedNanos, long durationNanos) {}
}
