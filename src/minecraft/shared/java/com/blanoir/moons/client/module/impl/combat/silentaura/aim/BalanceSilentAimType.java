package com.blanoir.moons.client.module.impl.combat.silentaura.aim;

import com.blanoir.moons.client.utils.rotation.Rotation;
import com.blanoir.moons.client.utils.rotation.aim.AimMotionNoise;
import com.blanoir.moons.client.utils.rotation.aim.HumanAimSimulator;

import net.minecraft.util.Mth;

/** Predictive tracking with sampled, humanized corrections. */
public final class BalanceSilentAimType implements SilentAimType {
    private long seed;
    private boolean initialized;

    @Override
    public boolean lock() {
        return false;
    }

    @Override
    public double sampleFlickYawScale() {
        return HumanAimSimulator.sampleVariation(0.82D, 1.06D);
    }

    @Override
    public double sampleFlickPitchScale() {
        return HumanAimSimulator.sampleVariation(0.82D, 1.04D);
    }

    @Override
    public double jitterScale() {
        return 0.72D;
    }

    @Override
    public double response(double smooth) {
        return 12.0D + smooth * 16.0D;
    }

    @Override
    public double maxYawSpeed() {
        return 660.0D;
    }

    @Override
    public double maxPitchSpeed() {
        return 440.0D;
    }

    @Override
    public double yawAssistScale() {
        return 0.95D;
    }

    @Override
    public double pitchAssistScale() {
        return 0.75D;
    }

    @Override
    public double pitchInertiaStrength() {
        return 0.86D;
    }

    @Override
    public double pitchCorridorSafetyFraction() {
        return 0.070D;
    }

    @Override
    public double pitchPredictionScale() {
        return 0.82D;
    }

    @Override
    public double aimSampleMinSeconds() {
        return 0.055D;
    }

    @Override
    public double aimSampleMaxSeconds() {
        return 0.115D;
    }

    @Override
    public double arriveEase(double settle, double settledJitter) {
        return 1.0D - 0.25D * settle * Mth.clamp(settledJitter, 0.0D, 1.0D);
    }

    @Override
    public Correction correct(
            Rotation desired, float currentYaw, double time, boolean targetChanged) {
        if (targetChanged || !initialized) {
            seed = System.nanoTime();
            initialized = true;
        }
        // Vary the response, never freeze the world-space aim direction. A held
        // yaw/pitch was already stale after a strafe or vertical-only movement.
        double responseScale = 1.0D + AimMotionNoise.sample(time * 2.6D, seed) * 0.14D;
        double accelerationScale =
                1.0D + AimMotionNoise.sample(time * 3.1D, seed ^ 0x94D049BB133111EBL) * 0.18D;
        return new Correction(desired, responseScale, accelerationScale);
    }

    @Override
    public void reset() {
        initialized = false;
        seed = 0L;
    }
}
