package com.blanoir.moons.client.module.impl.world.scaffold;

import com.blanoir.moons.client.utils.math.RandomMath;
import com.blanoir.moons.client.utils.rotation.Rotation;
import com.blanoir.moons.client.utils.rotation.aim.AimMotionSampling;
import com.blanoir.moons.client.utils.rotation.aim.BlockAim;
import com.blanoir.moons.client.utils.rotation.aim.BlockTarget;
import com.blanoir.moons.client.utils.rotation.aim.FaceAimJitter;
import com.blanoir.moons.client.utils.rotation.aim.GodBridgeFacePoints;
import com.blanoir.moons.client.utils.rotation.aim.PlacementAngleSolver;
import com.blanoir.moons.client.utils.rotation.quantize.MouseAngleQuantizer;
import com.blanoir.moons.client.utils.rotation.quantize.PacketStepQuantizer;
import com.blanoir.moons.client.utils.rotation.smooth.PacketRotationStep;
import com.blanoir.moons.client.utils.world.placement.PlacementRaycast;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.function.DoubleBinaryOperator;
import java.util.function.Function;

/** Scaffold-owned history using the same point noise and packet primitives as SilentAura. */
final class ScaffoldAimMotion {
    private final DoubleBinaryOperator random;
    private Rotation sent = new Rotation(0, 0);
    private float previousYaw;
    private float previousPitch;
    private AimMotionSampling.PacketMotion motion;
    private int sampledTick = Integer.MIN_VALUE;
    private Rotation sampled;
    private Rotation sampledBase;
    private float yawMinimum;
    private float yawMaximum;
    private float pitchMinimum;
    private float pitchMaximum;
    private long seed;
    private int noiseStartTick = Integer.MIN_VALUE;
    private BlockTarget pointTarget;
    private Vec3 pointAnchor;

    ScaffoldAimMotion() {
        this(RandomMath::between);
    }

    ScaffoldAimMotion(DoubleBinaryOperator random) {
        this.random = random;
    }

    synchronized void rebase(Rotation start) {
        sent = start;
        previousYaw = previousPitch = 0;
        motion = null;
        sampledTick = noiseStartTick = Integer.MIN_VALUE;
        sampled = sampledBase = null;
        pointTarget = null;
        pointAnchor = null;
        seed = RandomMath.nextLong();
    }

    /** Candidate reads never advance velocity; only real movement sends do. */
    synchronized void confirm(Rotation actual) {
        previousYaw = Mth.wrapDegrees(actual.yaw() - sent.yaw());
        previousPitch = actual.pitch() - sent.pitch();
        sent = actual;
    }

    synchronized BlockAim varyPoint(
            Minecraft client,
            PlacementRaycast rays,
            BlockAim fallback,
            Vec3 eye,
            Rotation base,
            MouseAngleQuantizer.Adapter quantizer,
            boolean godBridge) {
        if (fallback == null) return null;
        AABB bounds;
        try {
            bounds =
                    client.level
                            .getBlockState(fallback.target().support())
                            .getShape(client.level, fallback.target().support())
                            .bounds()
                            .move(fallback.target().support());
        } catch (RuntimeException emptyShape) {
            return fallback;
        }
        return varyPoint(
                fallback,
                eye,
                bounds,
                client.player.tickCount,
                point -> {
                    Rotation rotation = PlacementAngleSolver.solve(eye, point, base, quantizer);
                    if (rotation == null) return null;
                    BlockHitResult hit =
                            rays.traceFace(
                                    client,
                                    eye,
                                    rotation.yaw(),
                                    rotation.pitch(),
                                    client.player.blockInteractionRange(),
                                    fallback.target().support(),
                                    fallback.target().face());
                    return hit != null
                                    && (!godBridge
                                            || GodBridgeFacePoints.insideFace(
                                                    hit, fallback.target()))
                            ? new BlockAim(fallback.target(), rotation, hit)
                            : null;
                });
    }

    synchronized BlockAim varyPoint(
            BlockAim fallback, Vec3 eye, AABB bounds, int tick, Function<Vec3, BlockAim> trace) {
        if (!fallback.target().equals(pointTarget)) {
            pointTarget = fallback.target();
            pointAnchor = fallback.hit().getLocation();
        }
        if (noiseStartTick == Integer.MIN_VALUE || tick < noiseStartTick) noiseStartTick = tick;
        double seconds = (tick - noiseStartTick) * .05D;
        for (double strength = .38D; strength >= .095D; strength *= .5D) {
            Vec3 point =
                    FaceAimJitter.sample(
                            eye,
                            pointAnchor,
                            bounds,
                            fallback.target().face(),
                            seconds,
                            seed,
                            strength,
                            .85D);
            BlockAim varied = trace.apply(point);
            if (varied != null) return varied;
        }
        // Re-anchor when movement or occlusion makes the old point unusable.
        pointAnchor = fallback.hit().getLocation();
        return fallback;
    }

    synchronized Rotation sample(
            int tick, Rotation desired, double sensitivity, double speed, int ticks) {
        if (sampledTick == tick) return sampled;
        sampledTick = tick;
        sampledBase = sent;
        float yawDemand = Mth.wrapDegrees(desired.yaw() - sent.yaw());
        float pitchDemand = desired.pitch() - sent.pitch();
        double urgency = Mth.clamp(Math.hypot(yawDemand, pitchDemand) / 55.0D, 0, 1);
        AimMotionSampling.PacketMotion next =
                AimMotionSampling.sampleResponsivePacketMotion(true, urgency, random);
        if (motion != null) {
            next =
                    new AimMotionSampling.PacketMotion(
                            Mth.lerp(.3D, motion.yawAcceleration(), next.yawAcceleration()),
                            Mth.lerp(.3D, motion.pitchAcceleration(), next.pitchAcceleration()),
                            0,
                            0);
        }
        motion = next;
        double cap = Math.max(0, speed);
        Rotation planned =
                PacketRotationStep.plan(
                        sent,
                        yawDemand,
                        pitchDemand,
                        previousYaw,
                        previousPitch,
                        motion.yawAcceleration(),
                        motion.pitchAcceleration(),
                        cap,
                        cap,
                        ticks);
        // Approach a reduced speed cap through the same acceleration interval as SilentAura.
        yawMinimum = (float) Math.max(-180, Math.min(-cap, previousYaw));
        yawMaximum = (float) Math.min(180, Math.max(cap, previousYaw));
        pitchMinimum = (float) Math.max(-90 - sent.pitch(), Math.min(-cap, previousPitch));
        pitchMaximum = (float) Math.min(90 - sent.pitch(), Math.max(cap, previousPitch));
        double quantum = MouseAngleQuantizer.mouseSensitivityGcd(sensitivity);
        float yawStep =
                PacketStepQuantizer.step(
                        Mth.wrapDegrees(planned.yaw() - sent.yaw()),
                        previousYaw,
                        motion.yawAcceleration(),
                        quantum,
                        yawMinimum,
                        yawMaximum);
        float pitchStep =
                PacketStepQuantizer.step(
                        planned.pitch() - sent.pitch(),
                        previousPitch,
                        motion.pitchAcceleration(),
                        quantum,
                        pitchMinimum,
                        pitchMaximum);
        yawMinimum = (float) Math.max(yawMinimum, previousYaw - motion.yawAcceleration());
        yawMaximum = (float) Math.min(yawMaximum, previousYaw + motion.yawAcceleration());
        pitchMinimum = (float) Math.max(pitchMinimum, previousPitch - motion.pitchAcceleration());
        pitchMaximum = (float) Math.min(pitchMaximum, previousPitch + motion.pitchAcceleration());
        // A sensitivity change may require the nearest grid line outside an empty interval.
        yawMinimum = Math.min(yawMinimum, yawStep);
        yawMaximum = Math.max(yawMaximum, yawStep);
        pitchMinimum = Math.min(pitchMinimum, pitchStep);
        pitchMaximum = Math.max(pitchMaximum, pitchStep);
        sampled = new Rotation(sent.yaw() + yawStep, sent.pitch() + pitchStep);
        return sampled;
    }

    /** Nearby duplicate-delta alternatives must still fit the final packet's motion budget. */
    synchronized boolean accepts(int tick, Rotation candidate) {
        if (sampledTick != tick || sampledBase == null) return false;
        float yawStep = Mth.wrapDegrees(candidate.yaw() - sampledBase.yaw());
        float pitchStep = candidate.pitch() - sampledBase.pitch();
        return yawStep >= yawMinimum - .0001F
                && yawStep <= yawMaximum + .0001F
                && pitchStep >= pitchMinimum - .0001F
                && pitchStep <= pitchMaximum + .0001F;
    }

    synchronized Rotation pending(int tick) {
        return sampledTick == tick ? sampled : null;
    }

    synchronized boolean settled() {
        return Math.abs(previousYaw) < .5F && Math.abs(previousPitch) < .5F;
    }
}
