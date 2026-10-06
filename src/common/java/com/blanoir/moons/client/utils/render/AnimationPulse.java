package com.blanoir.moons.client.utils.render;

import java.util.function.LongSupplier;

/** Explicit render-only pulses; overlapping hits queue instead of restarting the current swing. */
public final class AnimationPulse {
    private final long durationNanos;
    private final LongSupplier clock;
    private long startedAt;
    private long endsAt;
    private boolean started;

    public AnimationPulse(long durationMillis) {
        this(durationMillis, System::nanoTime);
    }

    public AnimationPulse(long durationMillis, LongSupplier clock) {
        if (durationMillis <= 0)
            throw new IllegalArgumentException("Animation duration must be positive");
        durationNanos = Math.multiplyExact(durationMillis, 1_000_000L);
        this.clock = clock;
    }

    public void start() {
        startedAt = clock.getAsLong();
        endsAt = startedAt + durationNanos;
        started = true;
    }

    public void enqueue() {
        long now = clock.getAsLong();
        if (!started || now >= endsAt) {
            startedAt = now;
            endsAt = now;
        }
        endsAt += durationNanos;
        started = true;
    }

    public double progress() {
        long now = clock.getAsLong();
        return started && now < endsAt
                ? (double) (Math.max(0, now - startedAt) % durationNanos) / durationNanos
                : 1;
    }

    public boolean active() {
        return started && clock.getAsLong() < endsAt;
    }

    public void reset() {
        started = false;
    }
}
