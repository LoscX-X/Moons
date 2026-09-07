package com.blanoir.moons.client.utils.time;

/** Per-renderer clock. Instances avoid unrelated render paths consuming one global delta. */
public final class FrameClock {
    private final double initialDelta;
    private long previousNanos;

    public FrameClock() {
        this(1.0D / 60.0D);
    }

    public FrameClock(double initialDelta) {
        this.initialDelta = initialDelta;
    }

    public double nextDeltaSeconds() {
        return nextDeltaSeconds(System.nanoTime());
    }

    /** Uses the caller's timestamp so every operation in a frame can share one sample. */
    public double nextDeltaSeconds(long now) {
        double delta =
                previousNanos == 0L ? initialDelta : (now - previousNanos) / 1_000_000_000.0D;
        previousNanos = now;
        return Math.max(0.0D, Math.min(0.1D, delta));
    }

    public void reset() {
        previousNanos = 0L;
    }
}
