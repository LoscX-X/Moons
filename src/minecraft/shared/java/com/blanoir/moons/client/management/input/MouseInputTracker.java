package com.blanoir.moons.client.management.input;

import java.util.ArrayDeque;

/**
 * Records the raw mouse deltas Minecraft accumulates per rendered frame and exposes a
 * smoothed velocity/acceleration estimate. Combat modules use it as a reference for
 * real player input so assist logic can yield instead of fighting the player's own
 * mouse movement.
 */
public final class MouseInputTracker {
    private static final int HISTORY_SIZE = 5;
    private static final long STALE_AFTER_NANOS = 120_000_000L;
    private static final double NANOS_PER_SECOND = 1_000_000_000.0D;
    private static final double MIN_DELTA_SECONDS = 1.0D / 1_000.0D;
    private static final double VELOCITY_ALPHA = 0.30D;
    private static final double ACCELERATION_ALPHA = 0.20D;
    private static final double IDLE_DECAY = 0.5D;

    private static final ArrayDeque<Sample> samples = new ArrayDeque<>();
    private static double smoothedVelocity;
    private static double smoothedAcceleration;
    private static long lastSampleNanos;

    private MouseInputTracker() {}

    /**
     * Called by the MouseHandler ASM hook before vanilla consumes the accumulated deltas.
     */
    public static void recordFrame(long nowNanos, double deltaX, double deltaY) {
        if (samples.size() == HISTORY_SIZE) {
            samples.removeFirst();
        }
        samples.addLast(new Sample(nowNanos, deltaX, deltaY));
        lastSampleNanos = nowNanos;
    }

    public static Motion currentMotion(long nowNanos) {
        while (!samples.isEmpty()
                && nowNanos - samples.peekFirst().timeNanos() > STALE_AFTER_NANOS) {
            samples.removeFirst();
        }

        if (samples.isEmpty()) {
            decay();
            return new Motion(smoothedVelocity, smoothedAcceleration, false);
        }

        double velocityX = 0.0D;
        double velocityY = 0.0D;
        double previousVelocity = 0.0D;
        double previousDeltaSeconds = MIN_DELTA_SECONDS;
        double acceleration = 0.0D;
        boolean hasPreviousVelocity = false;
        Sample previous = null;

        for (Sample sample : samples) {
            if (previous != null) {
                double deltaSeconds =
                        Math.max(
                                (sample.timeNanos() - previous.timeNanos()) / NANOS_PER_SECOND,
                                MIN_DELTA_SECONDS);
                velocityX = sample.deltaX() / deltaSeconds;
                velocityY = sample.deltaY() / deltaSeconds;
                double velocity = Math.hypot(velocityX, velocityY);
                if (hasPreviousVelocity) {
                    acceleration =
                            Math.abs(velocity - previousVelocity)
                                    / ((deltaSeconds + previousDeltaSeconds) * 0.5D);
                }
                previousVelocity = velocity;
                previousDeltaSeconds = deltaSeconds;
                hasPreviousVelocity = true;
            }
            previous = sample;
        }

        double instantVelocity = Math.hypot(velocityX, velocityY);
        smoothedVelocity += (instantVelocity - smoothedVelocity) * VELOCITY_ALPHA;
        smoothedAcceleration += (acceleration - smoothedAcceleration) * ACCELERATION_ALPHA;

        boolean recentlyMoved =
                nowNanos - lastSampleNanos <= STALE_AFTER_NANOS
                        && (smoothedVelocity > 1.0D || smoothedAcceleration > 100.0D);
        return new Motion(smoothedVelocity, smoothedAcceleration, recentlyMoved);
    }

    private static void decay() {
        smoothedVelocity *= IDLE_DECAY;
        smoothedAcceleration *= IDLE_DECAY;
    }

    public record Motion(
            double velocityPxPerSecond,
            double accelerationPxPerSecondSquared,
            boolean recentlyMoved) {}

    private record Sample(long timeNanos, double deltaX, double deltaY) {}
}
