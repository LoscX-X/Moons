package com.blanoir.moons.client.utils.prediction;

import net.minecraft.world.phys.Vec3;

import java.util.Objects;

/** Tick-domain target motion. Velocities are blocks/tick; horizons are ticks. */
public final class MotionPrediction {
    private final Parameters parameters;
    private int observedTick = Integer.MIN_VALUE;
    private Vec3 position;
    private Vec3 velocity = Vec3.ZERO;
    private Vec3 acceleration = Vec3.ZERO;
    private Vec3 measuredVelocity = Vec3.ZERO;
    private Vec3 measuredAcceleration = Vec3.ZERO;
    private double measuredTurnRate;
    private double turnRate;
    private double measuredSpeedAcceleration;
    private double speedAcceleration;

    public MotionPrediction() {
        this(Parameters.DEFAULT);
    }

    public MotionPrediction(Parameters parameters) {
        this.parameters = Objects.requireNonNull(parameters, "parameters");
    }

    public Parameters parameters() {
        return parameters;
    }

    /** Confirmed horizontal movement turn rate, not the player's camera rotation. */
    public double turnRateDegreesPerTick() {
        return Math.toDegrees(turnRate);
    }

    public void observe(int tick, Vec3 currentPosition, Vec3 initialVelocity) {
        if (tick == observedTick) return;
        if (!finite(currentPosition)) {
            reset();
            return;
        }
        if (position == null
                || tick < observedTick
                || (long) tick - observedTick > parameters.maxObservationGapTicks()) {
            velocity = bounded(initialVelocity, parameters.maxSpeed());
            measuredVelocity = velocity;
            acceleration = Vec3.ZERO;
            measuredAcceleration = Vec3.ZERO;
            resetTurning();
        } else {
            double elapsed = (long) tick - observedTick;
            Vec3 travel = currentPosition.subtract(position);
            Vec3 measured = travel.scale(1.0D / elapsed);
            if (!finite(measured) || measured.length() > parameters.maxSpeed()) {
                // Teleports/discontinuities must not become a long predicted sweep.
                velocity = acceleration = Vec3.ZERO;
                measuredVelocity = measuredAcceleration = Vec3.ZERO;
                resetTurning();
            } else {
                // A forward strafe can reverse X while Y/Z still dominate the dot
                // product. Update each axis independently to discard stale lead.
                Vec3 nextVelocity =
                        new Vec3(
                                trackVelocity(velocity.x, measured.x, elapsed),
                                trackVelocity(velocity.y, measured.y, elapsed),
                                trackVelocity(velocity.z, measured.z, elapsed));
                Vec3 nextAcceleration =
                        elapsed == 1.0D
                                ? bounded(
                                        measured.subtract(measuredVelocity),
                                        parameters.maxAcceleration())
                                : Vec3.ZERO;
                // Filter catch-up is not physical acceleration. Extrapolate only
                // a trend seen in two consecutive measured velocity changes.
                acceleration =
                        new Vec3(
                                confirmedAcceleration(
                                        velocity.x,
                                        measured.x,
                                        nextAcceleration.x,
                                        measuredAcceleration.x),
                                confirmedAcceleration(
                                        velocity.y,
                                        measured.y,
                                        nextAcceleration.y,
                                        measuredAcceleration.y),
                                confirmedAcceleration(
                                        velocity.z,
                                        measured.z,
                                        nextAcceleration.z,
                                        measuredAcceleration.z));
                observeTurning(measured, elapsed);
                velocity = bounded(nextVelocity, parameters.maxSpeed());
                measuredVelocity = measured;
                measuredAcceleration = nextAcceleration;
            }
        }
        position = currentPosition;
        observedTick = tick;
    }

    public Vec3 displacement(double horizonTicks) {
        if (!Double.isFinite(horizonTicks)) return Vec3.ZERO;
        double horizon = Math.clamp(horizonTicks, 0.0D, parameters.maxHorizonTicks());
        // Acceleration confidence fades with the forecast horizon; noisy remote
        // interpolation should never dominate the measured velocity.
        double accelerationWeight = 0.5D * horizon * (horizon / (1.0D + horizon));
        Vec3 horizontal =
                turnRate == 0.0D
                        ? new Vec3(
                                forecast(velocity.x, acceleration.x, horizon, accelerationWeight),
                                0.0D,
                                forecast(velocity.z, acceleration.z, horizon, accelerationWeight))
                        : turningDisplacement(horizon, accelerationWeight);
        Vec3 travel =
                bounded(
                        new Vec3(
                                horizontal.x,
                                forecast(velocity.y, acceleration.y, horizon, accelerationWeight),
                                horizontal.z),
                        parameters.maxSpeed() * horizon);
        return new Vec3(travel.x, travel.y * parameters.verticalScale(), travel.z);
    }

    public Vec3 velocity() {
        return velocity;
    }

    public void reset() {
        observedTick = Integer.MIN_VALUE;
        position = null;
        velocity = acceleration = Vec3.ZERO;
        measuredVelocity = measuredAcceleration = Vec3.ZERO;
        resetTurning();
    }

    private double trackVelocity(double previous, double measured, double elapsed) {
        if (Math.abs(measured) < 1.0E-4D) return 0.0D;
        if (previous * measured <= 0.0D) return measured;
        double change =
                Math.clamp(
                        3.0D
                                * Math.abs(measured - previous)
                                / Math.max(0.05D, Math.max(Math.abs(previous), Math.abs(measured))),
                        0.0D,
                        1.0D);
        // Keep small interpolation noise smooth; large changes need a shorter
        // response time so acceleration and braking do not trail by several ticks.
        double responseTicks =
                parameters.maxResponseTicks()
                        + (parameters.minResponseTicks() - parameters.maxResponseTicks()) * change;
        if (responseTicks == 0.0D) return measured;
        return previous + (measured - previous) * (1.0D - Math.exp(-elapsed / responseTicks));
    }

    private void observeTurning(Vec3 measured, double elapsed) {
        double previousSpeed = Math.hypot(measuredVelocity.x, measuredVelocity.z);
        double currentSpeed = Math.hypot(measured.x, measured.z);
        double dot = measuredVelocity.x * measured.x + measuredVelocity.z * measured.z;
        if (parameters.maxTurnRateDegreesPerTick() == 0.0D
                || parameters.maxTurnAngleDegrees() == 0.0D
                || elapsed != 1.0D
                || previousSpeed < 1.0E-4D
                || currentSpeed < 1.0E-4D
                || dot <= 0.0D) {
            // Stops, sharp reversals and gaps are new motion, not evidence of an arc.
            resetTurning();
            return;
        }
        double nextRate =
                Math.atan2(measuredVelocity.x * measured.z - measuredVelocity.z * measured.x, dot);
        turnRate =
                nextRate * measuredTurnRate > 0.0D
                        ? Math.copySign(
                                Math.min(
                                        Math.min(Math.abs(nextRate), Math.abs(measuredTurnRate)),
                                        Math.toRadians(parameters.maxTurnRateDegreesPerTick())),
                                nextRate)
                        : 0.0D;
        measuredTurnRate = nextRate;
        double nextAcceleration =
                Math.clamp(
                        currentSpeed - previousSpeed,
                        -parameters.maxAcceleration(),
                        parameters.maxAcceleration());
        speedAcceleration =
                confirmedAcceleration(
                        previousSpeed, currentSpeed, nextAcceleration, measuredSpeedAcceleration);
        measuredSpeedAcceleration = nextAcceleration;
    }

    private Vec3 turningDisplacement(double horizon, double accelerationWeight) {
        double speed = Math.hypot(velocity.x, velocity.z);
        if (speed < 1.0E-4D) return Vec3.ZERO;
        double angleLimit = Math.toRadians(parameters.maxTurnAngleDegrees());
        double angle = Math.clamp(turnRate * horizon, -angleLimit, angleLimit);
        double halfAngle = angle * 0.5D;
        double chordScale = Math.abs(halfAngle) < 1.0E-8D ? 1.0D : Math.sin(halfAngle) / halfAngle;
        // Integrate a constant-turn arc. Confirmed changes of speed affect its
        // length; Cartesian acceleration must not count the same turn twice.
        double distance = forecast(speed, speedAcceleration, horizon, accelerationWeight);
        double scale = distance * chordScale / speed;
        double cos = Math.cos(halfAngle);
        double sin = Math.sin(halfAngle);
        return new Vec3(
                (velocity.x * cos - velocity.z * sin) * scale,
                0.0D,
                (velocity.x * sin + velocity.z * cos) * scale);
    }

    private void resetTurning() {
        measuredTurnRate = turnRate = 0.0D;
        measuredSpeedAcceleration = speedAcceleration = 0.0D;
    }

    private static double confirmedAcceleration(
            double previousVelocity, double measured, double current, double previous) {
        if (Math.abs(measured) < 1.0E-4D
                || previousVelocity * measured <= 0.0D
                || current * previous <= 0.0D) return 0.0D;
        return Math.copySign(Math.min(Math.abs(current), Math.abs(previous)), current);
    }

    private static double forecast(
            double speed, double acceleration, double horizon, double accelerationWeight) {
        double travel = speed * horizon + acceleration * accelerationWeight;
        // Braking can reach a stop, but is not evidence of a future reversal.
        return speed * travel <= 0.0D ? 0.0D : travel;
    }

    private static Vec3 bounded(Vec3 vector, double maximum) {
        if (!finite(vector)) return Vec3.ZERO;
        double length = vector.length();
        return length > maximum ? vector.scale(maximum / length) : vector;
    }

    private static boolean finite(Vec3 vector) {
        return vector != null
                && Double.isFinite(vector.x)
                && Double.isFinite(vector.y)
                && Double.isFinite(vector.z);
    }

    /**
     * Per-tracker limits. Speeds use blocks/tick, accelerations blocks/tick^2,
     * response times and horizons ticks, and turn limits degrees. Turning is
     * opt-in: the defaults preserve the existing Cartesian prediction.
     * maxSpeed also identifies discontinuous observations and must cover valid motion.
     */
    public record Parameters(
            double maxSpeed,
            double maxAcceleration,
            double maxHorizonTicks,
            double verticalScale,
            double minResponseTicks,
            double maxResponseTicks,
            double maxTurnRateDegreesPerTick,
            double maxTurnAngleDegrees,
            int maxObservationGapTicks) {
        public static final Parameters DEFAULT =
                new Parameters(1.5D, 0.12D, 3.0D, 0.35D, 0.35D, 1.5D, 0.0D, 90.0D, 5);

        public Parameters {
            nonNegativeFinite("maxSpeed", maxSpeed);
            if (maxSpeed == 0.0D) throw new IllegalArgumentException("maxSpeed must be positive");
            nonNegativeFinite("maxAcceleration", maxAcceleration);
            nonNegativeFinite("maxHorizonTicks", maxHorizonTicks);
            nonNegativeFinite("verticalScale", verticalScale);
            if (verticalScale > 1.0D)
                throw new IllegalArgumentException("verticalScale must be within [0, 1]");
            nonNegativeFinite("minResponseTicks", minResponseTicks);
            nonNegativeFinite("maxResponseTicks", maxResponseTicks);
            if (minResponseTicks > maxResponseTicks)
                throw new IllegalArgumentException(
                        "minResponseTicks must not exceed maxResponseTicks");
            nonNegativeFinite("maxTurnRateDegreesPerTick", maxTurnRateDegreesPerTick);
            nonNegativeFinite("maxTurnAngleDegrees", maxTurnAngleDegrees);
            if (maxTurnRateDegreesPerTick > 180.0D || maxTurnAngleDegrees > 180.0D)
                throw new IllegalArgumentException("turn limits must not exceed 180 degrees");
            if (maxObservationGapTicks < 1)
                throw new IllegalArgumentException("maxObservationGapTicks must be positive");
            if (!Double.isFinite(maxSpeed * maxHorizonTicks))
                throw new IllegalArgumentException("maximum displacement must be finite");
        }

        public static Builder builder() {
            return new Builder();
        }

        private static void nonNegativeFinite(String name, double value) {
            if (!Double.isFinite(value) || value < 0.0D)
                throw new IllegalArgumentException(name + " must be finite and nonnegative");
        }

        public static final class Builder {
            private double maxSpeed = DEFAULT.maxSpeed();
            private double maxAcceleration = DEFAULT.maxAcceleration();
            private double maxHorizonTicks = DEFAULT.maxHorizonTicks();
            private double verticalScale = DEFAULT.verticalScale();
            private double minResponseTicks = DEFAULT.minResponseTicks();
            private double maxResponseTicks = DEFAULT.maxResponseTicks();
            private double maxTurnRateDegreesPerTick = DEFAULT.maxTurnRateDegreesPerTick();
            private double maxTurnAngleDegrees = DEFAULT.maxTurnAngleDegrees();
            private int maxObservationGapTicks = DEFAULT.maxObservationGapTicks();

            private Builder() {}

            public Builder maxSpeed(double value) {
                maxSpeed = value;
                return this;
            }

            public Builder maxAcceleration(double value) {
                maxAcceleration = value;
                return this;
            }

            public Builder maxHorizonTicks(double value) {
                maxHorizonTicks = value;
                return this;
            }

            public Builder verticalScale(double value) {
                verticalScale = value;
                return this;
            }

            /** Shorter response follows changes faster; zero disables velocity smoothing. */
            public Builder responseTicks(double min, double max) {
                minResponseTicks = min;
                maxResponseTicks = max;
                return this;
            }

            /** Zero disables turn extrapolation; observations themselves are never turn-limited. */
            public Builder maxTurnRateDegreesPerTick(double value) {
                maxTurnRateDegreesPerTick = value;
                return this;
            }

            /** Maximum heading change over the entire forecast arc, in degrees. */
            public Builder maxTurnAngleDegrees(double value) {
                maxTurnAngleDegrees = value;
                return this;
            }

            public Builder maxObservationGapTicks(int value) {
                maxObservationGapTicks = value;
                return this;
            }

            public Parameters build() {
                return new Parameters(
                        maxSpeed,
                        maxAcceleration,
                        maxHorizonTicks,
                        verticalScale,
                        minResponseTicks,
                        maxResponseTicks,
                        maxTurnRateDegreesPerTick,
                        maxTurnAngleDegrees,
                        maxObservationGapTicks);
            }
        }
    }
}
