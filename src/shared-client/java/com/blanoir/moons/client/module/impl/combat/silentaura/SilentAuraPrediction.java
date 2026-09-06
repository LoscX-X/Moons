package com.blanoir.moons.client.module.impl.combat.silentaura;

import net.minecraft.world.phys.Vec3;

/** Tick-domain target motion. Velocities are blocks/tick; horizons are ticks. */
public final class SilentAuraPrediction {
    private static final double MAX_SPEED = 1.5D;
    private int observedTick = Integer.MIN_VALUE;
    private Vec3 position;
    private Vec3 velocity = Vec3.ZERO;
    private Vec3 acceleration = Vec3.ZERO;

    public void observe(int tick, Vec3 currentPosition, Vec3 initialVelocity) {
        if (tick == observedTick) return;
        if (position == null || tick < observedTick || (long) tick - observedTick > 5L) {
            velocity = bounded(initialVelocity, MAX_SPEED);
            acceleration = Vec3.ZERO;
        } else {
            double elapsed = (long) tick - observedTick;
            Vec3 travel = currentPosition.subtract(position);
            Vec3 measured = travel.scale(1.0D / elapsed);
            if (measured.lengthSqr() > MAX_SPEED * MAX_SPEED) {
                // Teleports/discontinuities must not become a long predicted sweep.
                velocity = acceleration = Vec3.ZERO;
            } else if (measured.lengthSqr() < 1.0E-6D || measured.dot(velocity) <= 0.0D) {
                // Stops and reversals invalidate forward momentum immediately.
                velocity = measured;
                acceleration = Vec3.ZERO;
            } else {
                Vec3 previous = velocity;
                velocity = velocity.lerp(measured, 1.0D - Math.exp(-elapsed / 1.5D));
                acceleration = bounded(velocity.subtract(previous).scale(1.0D / elapsed), 0.12D);
            }
        }
        position = currentPosition;
        observedTick = tick;
    }

    public Vec3 displacement(double horizonTicks) {
        double horizon = Math.max(0.0D, Math.min(3.0D, horizonTicks));
        // Acceleration confidence fades with the forecast horizon; noisy remote
        // interpolation should never dominate the measured velocity.
        Vec3 travel = velocity.scale(horizon).add(
                acceleration.scale(0.5D * horizon * horizon / (1.0D + horizon)));
        return new Vec3(travel.x, travel.y * 0.35D, travel.z);
    }

    public Vec3 velocity() { return velocity; }

    public void reset() {
        observedTick = Integer.MIN_VALUE;
        position = null;
        velocity = acceleration = Vec3.ZERO;
    }

    private static Vec3 bounded(Vec3 vector, double maximum) {
        if (vector == null || !Double.isFinite(vector.x)
                || !Double.isFinite(vector.y) || !Double.isFinite(vector.z)) return Vec3.ZERO;
        double length = vector.length();
        return length > maximum ? vector.scale(maximum / length) : vector;
    }
}
