package com.blanoir.moons.client.utils;

import com.blanoir.moons.client.utils.prediction.AimPrediction;
import com.blanoir.moons.client.utils.prediction.CooldownPrediction;
import com.blanoir.moons.client.utils.prediction.LandingPrediction;
import com.blanoir.moons.client.utils.prediction.MotionPrediction;
import com.blanoir.moons.client.utils.prediction.TrajectoryPrediction;
import com.blanoir.moons.client.utils.prediction.VerticalPrediction;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Behavior contracts for shared prediction algorithms; no game session required. */
public final class PredictionVerification {
    private static int checks;

    private PredictionVerification() {}

    public static void main(String[] args) {
        prediction();
        predictionMotionChanges();
        predictionSampling();
        predictionParameters();
        predictionTurning();
        trajectories();
        landing();
        jumpCycles();
        aimAndCooldown();
        System.out.println("Prediction verification passed: " + checks + " assertions");
    }

    private static void prediction() {
        MotionPrediction prediction = new MotionPrediction();
        Vec3 velocity = new Vec3(0.2D, 0.0D, 0.0D);
        prediction.observe(0, Vec3.ZERO, velocity);
        near(prediction.displacement(1.0D).x, 0.2D, 1.0E-8D, "one tick lead uses blocks/tick");
        prediction.observe(0, new Vec3(100, 0, 0), Vec3.ZERO);
        near(prediction.displacement(1).x, 0.2D, 1.0E-8D, "render calls do not resample a tick");
        prediction.observe(1, velocity, velocity);
        prediction.observe(2, velocity, Vec3.ZERO);
        near(prediction.displacement(2).length(), 0, 1.0E-8D, "stop discards momentum");
        prediction.observe(3, Vec3.ZERO, velocity.scale(-1));
        check(prediction.displacement(1).x < 0, "reversal predicts new direction");
        prediction.observe(4, new Vec3(100, 0, 0), velocity);
        near(prediction.displacement(2).length(), 0, 1.0E-8D, "teleport does not create lead");
        prediction.reset();
        near(prediction.displacement(2).length(), 0, 1.0E-8D, "reset discards prediction");
        prediction.observe(9, Vec3.ZERO, velocity);
        near(prediction.displacement(0).length(), 0, 1.0E-8D, "disabled lead stays disabled");
        check(
                prediction.displacement(0.8625D).x > prediction.displacement(0.6375D).x,
                "Lock horizon is stronger than Balance horizon");
    }

    private static void predictionMotionChanges() {
        MotionPrediction prediction = new MotionPrediction();
        Vec3 diagonal = new Vec3(0.2D, 0.3D, 0.3D);
        prediction.observe(0, Vec3.ZERO, diagonal);
        Vec3 reversed = new Vec3(-0.2D, 0.3D, 0.3D);
        prediction.observe(1, reversed, reversed);
        near(
                prediction.displacement(1).x,
                -0.2D,
                1.0E-8D,
                "a strafe reversal takes effect even while forward/vertical motion continues");
        Vec3 position = reversed.add(0, 0.3D, 0.3D);
        prediction.observe(2, position, new Vec3(0, 0.3D, 0.3D));
        near(
                prediction.displacement(3).x,
                0,
                1.0E-8D,
                "stopping only the strafe axis discards all its lead");
        near(
                prediction.displacement(1).z,
                0.3D,
                1.0E-8D,
                "a strafe change preserves steady forward tracking");

        prediction.reset();
        prediction.observe(0, Vec3.ZERO, new Vec3(0.1D, 0, 0));
        position = Vec3.ZERO;
        for (int tick = 1; tick <= 6; tick++) {
            Vec3 speed = new Vec3(0.1D + 0.04D * tick, 0, 0);
            position = position.add(speed);
            prediction.observe(tick, position, speed);
        }
        near(
                prediction.displacement(1).x,
                0.38D,
                0.055D,
                "sustained acceleration forecasts the next tick within 0.055 blocks");

        prediction.reset();
        prediction.observe(0, Vec3.ZERO, new Vec3(0.4D, 0, 0));
        prediction.observe(1, new Vec3(0.05D, 0, 0), Vec3.ZERO);
        near(
                prediction.displacement(1).x,
                0.05D,
                0.025D,
                "braking loses stale speed within one observation");

        prediction.reset();
        prediction.observe(0, Vec3.ZERO, new Vec3(0.3D, 0, 0));
        position = Vec3.ZERO;
        int motionTick = 0;
        for (double speed : new double[] {0.2D, 0.1D, 0.005D}) {
            position = position.add(speed, 0, 0);
            prediction.observe(++motionTick, position, Vec3.ZERO);
            check(
                    prediction.displacement(3).x >= 0,
                    "braking never invents a reversal before it is observed");
        }

        prediction.reset();
        prediction.observe(0, Vec3.ZERO, new Vec3(0.1D, 0, 0));
        position = Vec3.ZERO;
        for (int tick = 1; tick <= 5; tick++) {
            position = position.add(0.3D, 0, 0);
            prediction.observe(tick, position, Vec3.ZERO);
            check(
                    prediction.displacement(3).x <= 0.9D + 1.0E-8D,
                    "a single speed step does not turn filter catch-up into acceleration");
        }

        prediction.reset();
        prediction.observe(0, Vec3.ZERO, new Vec3(0.2D, 0, 0));
        position = Vec3.ZERO;
        for (int tick = 1; tick <= 40; tick++) {
            position = position.add(0.2D + (tick % 2 == 0 ? 0.005D : -0.005D), 0, 0);
            prediction.observe(tick, position, Vec3.ZERO);
            near(
                    prediction.displacement(3).x,
                    0.6D,
                    0.015D,
                    "alternating interpolation noise keeps long lead near constant speed");
        }
    }

    private static void predictionSampling() {
        MotionPrediction prediction = new MotionPrediction();
        Vec3 speed = new Vec3(0.2D, 0, 0);
        prediction.observe(0, Vec3.ZERO, speed);
        prediction.observe(3, speed.scale(3), Vec3.ZERO);
        near(
                prediction.displacement(1).x,
                speed.x,
                1.0E-8D,
                "skipped observations use elapsed ticks instead of inflating speed");
        near(
                prediction.displacement(100).x,
                prediction.displacement(3).x,
                0,
                "long requested horizons remain capped at three ticks");
        near(prediction.displacement(-1).length(), 0, 0, "negative horizons remain disabled");
        near(
                prediction.displacement(Double.NaN).length(),
                0,
                0,
                "invalid horizons cannot poison aim");
        prediction.observe(10, new Vec3(100, 0, 0), speed.scale(-1));
        near(
                prediction.displacement(1).x,
                -0.2D,
                1.0E-8D,
                "stale tracks reseed without carrying acceleration across the gap");
        prediction.observe(11, new Vec3(Double.NaN, 0, 0), speed);
        near(
                prediction.displacement(1).length(),
                0,
                0,
                "invalid positions discard the old prediction");
        prediction.observe(12, Vec3.ZERO, speed);
        near(
                prediction.displacement(1).x,
                0.2D,
                1.0E-8D,
                "a valid sample recovers after invalid input");

        prediction.reset();
        prediction.observe(0, Vec3.ZERO, new Vec3(1.5D, 0, 0));
        prediction.observe(1, new Vec3(1.49D, 0, 0.17D), Vec3.ZERO);
        check(
                prediction.velocity().length() <= 1.5D + 1.0E-8D,
                "independent axis responses preserve the total speed bound");
        check(
                prediction.displacement(3).length() <= 4.5D + 1.0E-8D,
                "forecast acceleration preserves the displacement bound");
    }

    private static void predictionParameters() {
        var parameters =
                MotionPrediction.Parameters.builder()
                        .maxSpeed(0.5D)
                        .maxAcceleration(0.0D)
                        .maxHorizonTicks(2.0D)
                        .verticalScale(1.0D)
                        .responseTicks(0.0D, 0.0D)
                        .maxObservationGapTicks(2)
                        .build();
        MotionPrediction prediction = new MotionPrediction(parameters);
        prediction.observe(0, Vec3.ZERO, new Vec3(0, 1, 0));
        near(
                prediction.displacement(10).y,
                1,
                1.0E-10D,
                "speed, horizon and vertical limits compose");
        prediction.observe(1, new Vec3(0.2D, 0, 0), Vec3.ZERO);
        prediction.observe(2, new Vec3(0.5D, 0, 0), Vec3.ZERO);
        prediction.observe(3, new Vec3(0.9D, 0, 0), Vec3.ZERO);
        near(
                prediction.displacement(2).x,
                .8D,
                1.0E-10D,
                "zero acceleration and response produce current-speed lead");
        prediction.observe(6, new Vec3(50, 0, 0), new Vec3(-.1D, 0, 0));
        near(prediction.displacement(1).x, -.1D, 1.0E-10D, "custom gap limit reseeds the track");
        prediction.observe(7, new Vec3(51, 0, 0), Vec3.ZERO);
        near(
                prediction.displacement(1).length(),
                0,
                0,
                "custom speed limit rejects discontinuities");

        MotionPrediction slow =
                new MotionPrediction(
                        MotionPrediction.Parameters.builder().responseTicks(3, 3).build());
        MotionPrediction fast =
                new MotionPrediction(
                        MotionPrediction.Parameters.builder().responseTicks(.1D, .1D).build());
        for (MotionPrediction track : new MotionPrediction[] {slow, fast}) {
            track.observe(0, Vec3.ZERO, new Vec3(.1D, 0, 0));
            track.observe(1, new Vec3(.3D, 0, 0), Vec3.ZERO);
        }
        check(
                fast.velocity().x > slow.velocity().x,
                "shorter response follows a speed change faster");

        rejected(() -> MotionPrediction.Parameters.builder().maxSpeed(0).build());
        rejected(() -> MotionPrediction.Parameters.builder().maxAcceleration(-1).build());
        rejected(() -> MotionPrediction.Parameters.builder().maxHorizonTicks(Double.NaN).build());
        rejected(() -> MotionPrediction.Parameters.builder().verticalScale(2).build());
        rejected(() -> MotionPrediction.Parameters.builder().responseTicks(2, 1).build());
        rejected(() -> MotionPrediction.Parameters.builder().responseTicks(-1, 1).build());
        rejected(
                () ->
                        MotionPrediction.Parameters.builder()
                                .maxTurnRateDegreesPerTick(Double.POSITIVE_INFINITY)
                                .build());
        rejected(() -> MotionPrediction.Parameters.builder().maxTurnAngleDegrees(181).build());
        rejected(() -> MotionPrediction.Parameters.builder().maxObservationGapTicks(0).build());
    }

    private static void predictionTurning() {
        for (double direction : new double[] {-1, 1}) {
            MotionPrediction prediction = turningTrack(10, 90);
            Vec3 position = Vec3.ZERO;
            prediction.observe(0, position, heading(0));
            position = position.add(heading(direction * 20));
            prediction.observe(1, position, Vec3.ZERO);
            near(
                    prediction.turnRateDegreesPerTick(),
                    0,
                    0,
                    "one turn sample cannot establish a trend");
            position = position.add(heading(direction * 40));
            prediction.observe(2, position, Vec3.ZERO);
            near(
                    prediction.turnRateDegreesPerTick(),
                    direction * 10,
                    1.0E-10D,
                    "turn rate honors configured cap and direction");
            Vec3 lead = prediction.displacement(2);
            near(
                    headingChange(prediction.velocity(), lead),
                    direction * 10,
                    1.0E-8D,
                    "arc displacement uses the integrated heading");
            double radians = Math.toRadians(10);
            near(
                    lead.length(),
                    .6D * Math.sin(radians) / radians,
                    1.0E-8D,
                    "constant-speed arc has the correct chord length");
            prediction.observe(3, position, Vec3.ZERO);
            near(prediction.displacement(3).length(), 0, 0, "stopping clears an active turn");
            near(prediction.turnRateDegreesPerTick(), 0, 0, "stop clears angular history");
        }

        MotionPrediction capped = turningTrack(30, 12);
        Vec3 position = Vec3.ZERO;
        capped.observe(0, position, heading(170));
        position = position.add(heading(-170));
        capped.observe(1, position, Vec3.ZERO);
        position = position.add(heading(-150));
        capped.observe(2, position, Vec3.ZERO);
        near(
                capped.turnRateDegreesPerTick(),
                20,
                1.0E-8D,
                "heading wrap does not invent a 340-degree turn");
        near(
                headingChange(capped.velocity(), capped.displacement(3)),
                6,
                1.0E-8D,
                "total turn cap bounds the full forecast arc");
        position = position.add(heading(30));
        capped.observe(3, position, Vec3.ZERO);
        near(capped.turnRateDegreesPerTick(), 0, 0, "observed reversal discards an old turn");
        check(
                capped.velocity().dot(heading(30)) > 0,
                "turn limit never delays an observed reversal");
        capped.reset();
        near(capped.displacement(2).length(), 0, 0, "reset clears arc prediction");

        MotionPrediction noisy = turningTrack(30, 90);
        position = Vec3.ZERO;
        noisy.observe(0, position, heading(0));
        for (int tick = 1; tick <= 12; tick++) {
            position = position.add(heading(tick % 2 == 0 ? -5 : 5));
            noisy.observe(tick, position, Vec3.ZERO);
            near(
                    noisy.turnRateDegreesPerTick(),
                    0,
                    0,
                    "alternating heading noise cannot create a persistent turn");
        }
        MotionPrediction stale = turningTrack(30, 90);
        position = Vec3.ZERO;
        stale.observe(0, position, heading(0));
        position = position.add(heading(20));
        stale.observe(1, position, Vec3.ZERO);
        position = position.add(heading(40));
        stale.observe(2, position, Vec3.ZERO);
        check(stale.turnRateDegreesPerTick() > 0, "setup has a confirmed turn");
        stale.observe(4, position.add(heading(60).scale(2)), Vec3.ZERO);
        near(
                stale.turnRateDegreesPerTick(),
                0,
                0,
                "missing samples invalidate turn acceleration assumptions");
    }

    private static MotionPrediction turningTrack(double maxRate, double maxAngle) {
        return new MotionPrediction(
                MotionPrediction.Parameters.builder()
                        .responseTicks(0, 0)
                        .maxTurnRateDegreesPerTick(maxRate)
                        .maxTurnAngleDegrees(maxAngle)
                        .build());
    }

    private static Vec3 heading(double degrees) {
        double radians = Math.toRadians(degrees);
        return new Vec3(.3D * Math.cos(radians), 0, .3D * Math.sin(radians));
    }

    private static double headingChange(Vec3 before, Vec3 after) {
        return Math.toDegrees(
                Math.atan2(before.x * after.z - before.z * after.x, before.dot(after)));
    }

    private static void rejected(Runnable action) {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            checks++;
            return;
        }
        throw new AssertionError("invalid prediction parameters accepted");
    }

    private static void trajectories() {
        Vec3 velocity = new Vec3(0.2D, -0.1D, 0.3D);
        var airborne = TrajectoryPrediction.advance(Vec3.ZERO, velocity, false, move -> move);
        check(
                airborne.position().equals(velocity),
                "collision trajectory moves before integrating gravity");
        near(airborne.velocity().y, -0.1764D, 1.0E-10D, "airborne gravity and drag");
        var wall =
                TrajectoryPrediction.advance(
                        Vec3.ZERO, velocity, false, move -> new Vec3(0, move.y, move.z));
        near(wall.velocity().x, 0, 0, "wall collision stops only the blocked axis");
        near(wall.velocity().z, 0.273D, 1.0E-10D, "wall sliding preserves free-axis drag");
        var ground =
                TrajectoryPrediction.advance(
                        Vec3.ZERO, new Vec3(0.2D, 0, 0), true, move -> new Vec3(move.x, 0, move.z));
        check(
                ground.grounded() && ground.velocity().y == 0,
                "downward support probe preserves grounded state");
        var edge =
                TrajectoryPrediction.advance(Vec3.ZERO, new Vec3(0.2D, 0, 0), true, move -> move);
        check(
                !edge.grounded() && edge.position().y < 0,
                "an unsupported ground step begins falling");
        Vec3 free = TrajectoryPrediction.freeFlightPosition(Vec3.ZERO, velocity, false, 1);
        near(
                free.x,
                0.182D,
                1.0E-10D,
                "free-flight safety path integrates horizontal drag before motion");
        near(
                free.y,
                -0.1764D,
                1.0E-10D,
                "free-flight safety path integrates gravity before motion");
        Vec3 flat = TrajectoryPrediction.freeFlightPosition(Vec3.ZERO, velocity, true, 2);
        near(flat.x, 0.382D, 1.0E-10D, "ground path retains two-tick horizontal travel");
        near(flat.y, 0, 0, "ground safety path does not extrapolate vertical movement");
        AABB box = new AABB(-0.3D, 0, -0.3D, 0.3D, 1.8D, 0.3D);
        near(
                TrajectoryPrediction.boundedLinearBox(box, velocity, 99).minX,
                0.3D,
                1.0E-10D,
                "bounded aim-box projection caps its horizon");
        near(
                TrajectoryPrediction.boundedLinearBox(box, velocity, -1).minX,
                box.minX,
                0,
                "bounded aim-box projection rejects past horizons");
        near(
                TrajectoryPrediction.linearBox(box, velocity, 6).minX,
                0.9D,
                1.0E-10D,
                "unbounded linear forecasts retain their caller horizon");
    }

    private static void landing() {
        check(
                LandingPrediction.ticksUntilGround(-0.1D, 0.05D) == 1,
                "near ground is reached on the first falling tick");
        check(
                LandingPrediction.ticksUntilGround(-0.1D, 0.2D) == 2,
                "fall-time integration keeps the original tick order");
        check(
                LandingPrediction.ticksUntilGround(0.1D, 0.2D) == Integer.MAX_VALUE,
                "rising motion has no immediate landing forecast");
        check(
                LandingPrediction.ticksUntilGround(-0.1D, Double.POSITIVE_INFINITY)
                        == Integer.MAX_VALUE,
                "missing ground remains unavailable");
        check(
                LandingPrediction.ticksUntilGround(-0.1D, 1.0E6D) == Integer.MAX_VALUE,
                "fall-time search stays bounded");
        check(
                new BlockPos(1, 9, 0)
                        .equals(
                                LandingPrediction.landingBlock(
                                        new Vec3(0.9D, 10, 0), new Vec3(0.2D, -0.1D, 0))),
                "landing-cell projection accounts for horizontal drift");
    }

    private static void jumpCycles() {
        var standing = new VerticalPrediction.Snapshot(0, 0, true, 0.42D, null, false);
        var idle = VerticalPrediction.forecast(standing, 24, 0.05D, false, false);
        for (var state : idle)
            check(
                    state.onGround() && !state.critical(),
                    "idle ground never creates a critical phase");
        var jump = VerticalPrediction.forecast(standing, 24, 0.05D, false, true);
        check(
                !jump[1].onGround() && jump[1].yOffset() > 0,
                "effective input starts the first jump");
        check(!jump[1].critical(), "rising jump does not become a critical");
        boolean descending = false;
        for (var state : jump) {
            descending |= state.critical();
            check(state.jumpCycle() <= 1, "synthetic input cannot invent repeated physical jumps");
        }
        check(descending && jump[24].onGround(), "a jump includes descent and eventually lands");
        var repeated = VerticalPrediction.forecast(standing, 36, 0.05D, true, true);
        check(repeated[36].jumpCycle() > 1, "held physical input permits later jump cycles");
        var slowFall = new VerticalPrediction.Snapshot(-0.1D, 0, false, 0.42D, null, true);
        for (var state : VerticalPrediction.forecast(slowFall, 5, 8, false, false)) {
            check(!state.critical(), "slow falling clears projected fall distance");
        }
        var levitation = new VerticalPrediction.Snapshot(-0.1D, 0, false, 0.42D, 0, false);
        for (var state : VerticalPrediction.forecast(levitation, 5, 8, false, false)) {
            check(!state.critical(), "levitation does not produce critical descent");
        }
    }

    private static void aimAndCooldown() {
        check(
                AimPrediction.estimateAlignmentTicks(0.6D, 0.5D, 0.6D) == 0,
                "aligned aim needs no waiting");
        check(
                AimPrediction.estimateAlignmentTicks(4.8D, 0.5D, 0.6D) == 3,
                "half-retention aim converges in three ticks");
        AABB box = new AABB(-0.3D, 0, -0.3D, 0.3D, 1.8D, 0.3D);
        Vec3 point = AimPrediction.clampedLead(new Vec3(0, 1, 0), new Vec3(10, 10, -10), box);
        check(
                point.equals(new Vec3(0.3D, 1.8D, -0.3D)),
                "lead remains inside caller-provided bounds");
        Vec3 eye = new Vec3(0, 1.6D, 0);
        near(
                AimPrediction.crossingLookaheadTicks(Vec3.ZERO, Vec3.ZERO, eye, box, 0.22D),
                0,
                0,
                "no relative crossing motion creates no lookahead");
        near(
                AimPrediction.crossingLookaheadTicks(
                        new Vec3(0.2D, 0, 0), Vec3.ZERO, eye, box, 0.22D),
                3.25D,
                1.0E-10D,
                "crossing preview includes exit distance and recovery margin");
        near(
                AimPrediction.crossingLookaheadTicks(
                        new Vec3(0.04D, 0, 0), Vec3.ZERO, eye, box, 0.22D),
                5,
                0,
                "slow crossing previews retain the five-tick cap");
        check(
                CooldownPrediction.ticksUntilThreshold(0.5D, 0.9D, 10) == 4,
                "cooldown threshold forecasts remaining ticks");
        check(
                CooldownPrediction.ticksUntilThreshold(1, 0.9D, 10) == 0,
                "ready cooldown needs no wait");
        near(CooldownPrediction.chargeAt(0.8D, 10, 5), 1, 0, "future charge saturates at full");
        check(
                CooldownPrediction.projectedOverchargeTicks(1, 10, 3, 2) == 5,
                "full charge preserves elapsed overcharge");
        check(
                CooldownPrediction.projectedOverchargeTicks(0.5D, 10, 7, 0) == 2,
                "future overcharge begins after reaching full charge");
    }

    private static void near(double actual, double expected, double tolerance, String message) {
        check(
                Double.isFinite(actual) && Math.abs(actual - expected) <= tolerance,
                message + ": " + actual + " expected " + expected);
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
