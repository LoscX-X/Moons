package com.blanoir.moons.client.management.targeting;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * Tracks the short, reliable ground-placement window after an attacked player
 * has actually left the ground and landed again. Merely observing
 * {@code onGround=true} is not sufficient because a normally running player is
 * grounded too and remains difficult to intercept with a world-fixed block.
 */
public final class PostHitLandingWindow {
    private int targetId = -1;
    private int remainingTicks;
    private int windowDurationTicks;
    private int windowTicks;
    private int lastTargetTick = Integer.MIN_VALUE;
    private Vec3 lastPosition;
    private boolean previousOnGround;
    private boolean sawAirborne;
    private boolean landingObserved;
    private double targetHorizontalSpeed = Double.POSITIVE_INFINITY;
    private double relativeHorizontalSpeed = Double.POSITIVE_INFINITY;

    public void arm(Player target, int lifetimeTicks, int landingWindowTicks) {
        arm(Sample.of(target), lifetimeTicks, landingWindowTicks);
    }

    public void arm(Sample target, int lifetimeTicks, int landingWindowTicks) {
        if (target == null) {
            clear();
            return;
        }
        if (target.targetId() == targetId
                && target.tick() >= lastTargetTick
                && !snapshot().expired()) {
            // Aura may attack again on the very tick the target lands. Keep
            // the last airborne sample so update can still detect that edge.
            // Refresh the request lifetime, never the already-open landing window.
            remainingTicks = Math.max(remainingTicks, Math.max(1, lifetimeTicks));
            windowDurationTicks = Math.max(1, landingWindowTicks);
            return;
        }
        targetId = target.targetId();
        remainingTicks = Math.max(1, lifetimeTicks);
        windowDurationTicks = Math.max(1, landingWindowTicks);
        windowTicks = 0;
        lastTargetTick = target.tick();
        lastPosition = target.position();
        previousOnGround = target.onGround();
        sawAirborne = !previousOnGround;
        landingObserved = false;
        targetHorizontalSpeed = Double.POSITIVE_INFINITY;
        relativeHorizontalSpeed = Double.POSITIVE_INFINITY;
    }

    public Snapshot update(Player target, Vec3 observerVelocity) {
        return update(Sample.of(target), observerVelocity);
    }

    public Snapshot update(Sample target, Vec3 observerVelocity) {
        if (target == null || target.targetId() != targetId || remainingTicks <= 0) {
            return snapshot();
        }
        if (target.tick() == lastTargetTick) {
            return snapshot();
        }

        if (target.tick() < lastTargetTick) {
            clear();
            return snapshot();
        }

        int elapsedTicks = (int) Math.min(Integer.MAX_VALUE, (long) target.tick() - lastTargetTick);
        remainingTicks = Math.max(0, remainingTicks - elapsedTicks);
        if (windowTicks > 0) {
            windowTicks = Math.max(0, windowTicks - elapsedTicks);
        }

        Vec3 position = target.position();
        Vec3 observed =
                lastPosition == null
                        ? Vec3.ZERO
                        : position.subtract(lastPosition).scale(1.0D / elapsedTicks);
        targetHorizontalSpeed = Math.hypot(observed.x, observed.z);
        Vec3 observer = observerVelocity == null ? Vec3.ZERO : observerVelocity;
        relativeHorizontalSpeed = Math.hypot(observed.x - observer.x, observed.z - observer.z);

        boolean onGround = target.onGround();
        if (!onGround) {
            sawAirborne = true;
        }
        if (sawAirborne && onGround && !previousOnGround) {
            landingObserved = true;
            windowTicks = windowDurationTicks;
        }

        previousOnGround = onGround;
        lastPosition = position;
        lastTargetTick = target.tick();
        return snapshot();
    }

    public boolean matches(Player target) {
        return target != null && target.getId() == targetId;
    }

    public void clear() {
        targetId = -1;
        remainingTicks = 0;
        windowDurationTicks = 0;
        windowTicks = 0;
        lastTargetTick = Integer.MIN_VALUE;
        lastPosition = null;
        previousOnGround = false;
        sawAirborne = false;
        landingObserved = false;
        targetHorizontalSpeed = Double.POSITIVE_INFINITY;
        relativeHorizontalSpeed = Double.POSITIVE_INFINITY;
    }

    private Snapshot snapshot() {
        return new Snapshot(
                targetId >= 0 && remainingTicks > 0,
                sawAirborne,
                landingObserved,
                windowTicks > 0,
                remainingTicks <= 0 || landingObserved && windowTicks <= 0,
                targetHorizontalSpeed,
                relativeHorizontalSpeed);
    }

    public record Snapshot(
            boolean armed,
            boolean sawAirborne,
            boolean landingObserved,
            boolean insideLandingWindow,
            boolean expired,
            double targetHorizontalSpeed,
            double relativeHorizontalSpeed) {}

    /** Tick-stamped observations also allow deterministic replay of attack/landing ordering. */
    public record Sample(int targetId, int tick, Vec3 position, boolean onGround) {
        private static Sample of(Player target) {
            return target == null
                    ? null
                    : new Sample(
                            target.getId(), target.tickCount, target.position(), target.onGround());
        }
    }
}
