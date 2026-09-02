package com.blanoir.moons.client.module.impl.combat.silentaura.aim;

import com.blanoir.moons.client.utils.rotation.aim.HumanAimSimulator;
import com.blanoir.moons.client.utils.rotation.Rotation;

/** Combat-first live tracking with fast head/body response. */
public final class LockSilentAimType implements SilentAimType {
    @Override public boolean lock() { return true; }
    @Override public double sampleFlickYawScale() { return HumanAimSimulator.sampleVariation(0.96D, 1.08D); }
    @Override public double sampleFlickPitchScale() { return HumanAimSimulator.sampleVariation(0.94D, 1.06D); }
    @Override public double sampleAccelerationNoise() { return HumanAimSimulator.sampleVariation(0.97D, 1.06D); }
    @Override public double jitterScale() { return 0.22D; }
    @Override public double response(double smooth) { return 19.0D + smooth * 23.0D; }
    @Override public double maxYawSpeed() { return 1_080.0D; }
    @Override public double maxPitchSpeed() { return 760.0D; }
    @Override public double yawAssistScale() { return 0.35D; }
    @Override public double pitchAssistScale() { return 0.28D; }
    @Override public double predictionResponse() { return 14.0D; }
    @Override public double predictionThreshold() { return 0.045D; }
    /** Default 0.5 lead now covers roughly the next packet tick. */
    @Override public double predictionLeadScale() { return 2.0D; }
    @Override public double predictionHorizonScale() { return 0.65D; }
    /** Retain pitch while the ray is safe, but keep a visible live correction. */
    @Override public double pitchInertiaStrength() { return 0.82D; }
    /** Lock leaves the corridor sooner so its attack ray keeps a larger margin. */
    @Override public double pitchCorridorSafetyFraction() { return 0.095D; }
    @Override public double pitchPredictionScale() { return 1.0D; }
    @Override public double aimSampleMinSeconds() { return 0.025D; }
    @Override public double aimSampleMaxSeconds() { return 0.055D; }
    @Override public double arriveEase(double settle, double settledJitter) { return 1.0D; }
    @Override public Correction correct(Rotation desired, float currentYaw, double time, boolean targetChanged) {
        return new Correction(desired, 1.0D, 1.0D);
    }
    @Override public void reset() { }
}
