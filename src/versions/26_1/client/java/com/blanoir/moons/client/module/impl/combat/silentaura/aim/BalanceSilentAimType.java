package com.blanoir.moons.client.module.impl.combat.silentaura.aim;

import com.blanoir.moons.client.utils.rotation.aim.HumanAimSimulator;
import com.blanoir.moons.client.utils.rotation.Rotation;
import net.minecraft.util.Mth;

/** Predictive tracking with sampled, humanized corrections. */
public final class BalanceSilentAimType implements SilentAimType {
    private Rotation heldRotation;
    private double nextCorrectionSeconds;
    private double responseScale = 1.0D;
    private double accelerationScale = 1.0D;

    @Override public boolean lock() { return false; }
    @Override public double sampleFlickYawScale() { return HumanAimSimulator.sampleVariation(0.82D, 1.06D); }
    @Override public double sampleFlickPitchScale() { return HumanAimSimulator.sampleVariation(0.82D, 1.04D); }
    @Override public double sampleAccelerationNoise() { return HumanAimSimulator.sampleVariation(0.90D, 1.12D); }
    @Override public double jitterScale() { return 0.72D; }
    @Override public double response(double smooth) { return 16.0D + smooth * 20.0D; }
    @Override public double maxYawSpeed() { return 900.0D; }
    @Override public double maxPitchSpeed() { return 620.0D; }
    @Override public double yawAssistScale() { return 0.95D; }
    @Override public double pitchAssistScale() { return 0.75D; }
    @Override public double predictionResponse() { return 12.0D; }
    @Override public double predictionThreshold() { return 0.02D; }
    @Override public double predictionLeadScale() { return 1.45D; }
    @Override public double predictionHorizonScale() { return 0.85D; }
    @Override public double pitchInertiaStrength() { return 0.86D; }
    @Override public double pitchCorridorSafetyFraction() { return 0.070D; }
    @Override public double pitchPredictionScale() { return 0.82D; }
    @Override public double aimSampleMinSeconds() { return 0.055D; }
    @Override public double aimSampleMaxSeconds() { return 0.115D; }
    @Override public double arriveEase(double settle, double settledJitter) {
        return 1.0D - 0.25D * settle * Mth.clamp(settledJitter, 0.0D, 1.0D);
    }

    @Override
    public Correction correct(Rotation desired, float currentYaw, double time, boolean targetChanged) {
        double demand = Math.abs(Mth.wrapDegrees(desired.yaw() - currentYaw));
        if (targetChanged || heldRotation == null || time >= nextCorrectionSeconds || demand > 20.0D) {
            heldRotation = desired;
            double urgency = Mth.clamp(demand / 45.0D, 0.0D, 1.0D);
            nextCorrectionSeconds = time + HumanAimSimulator.sampleVariation(
                    0.030D, 0.075D - urgency * 0.025D);
            responseScale = HumanAimSimulator.sampleVariation(0.84D, 1.20D);
            accelerationScale = HumanAimSimulator.sampleVariation(0.75D, 1.25D);
        }
        return new Correction(heldRotation, responseScale, accelerationScale);
    }

    @Override
    public void reset() {
        heldRotation = null;
        nextCorrectionSeconds = 0.0D;
        responseScale = accelerationScale = 1.0D;
    }
}
