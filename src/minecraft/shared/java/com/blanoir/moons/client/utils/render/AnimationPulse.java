package com.blanoir.moons.client.utils.render;

/** One render-only animation window, started explicitly and never extended by rendering. */
public final class AnimationPulse {
    private final long durationNanos;
    private long startedAt;
    private boolean started;

    public AnimationPulse(long durationMillis) {
        if (durationMillis <= 0)
            throw new IllegalArgumentException("Animation duration must be positive");
        durationNanos = Math.multiplyExact(durationMillis, 1_000_000L);
    }

    public void start() {
        startedAt = System.nanoTime();
        started = true;
    }

    public double progress() {
        return started
                ? Math.clamp((double) (System.nanoTime() - startedAt) / durationNanos, 0, 1)
                : 1;
    }

    public boolean active() {
        return started && progress() < 1;
    }

    public void reset() {
        started = false;
    }
}
