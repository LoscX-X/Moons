package com.blanoir.moons.client.module.impl.combat.silentaura.aim;

import com.blanoir.moons.client.utils.rotation.Rotation;

/** Mode-specific SilentAura rotation behavior. */
public interface SilentAimType {
    boolean lock();
    double sampleFlickYawScale();
    double sampleFlickPitchScale();
    double sampleAccelerationNoise();
    double jitterScale();
    double response(double smooth);
    double maxYawSpeed();
    double maxPitchSpeed();
    double yawAssistScale();
    double pitchAssistScale();
    double predictionResponse();
    double predictionThreshold();
    double predictionLeadScale();
    double predictionHorizonScale();
    double pitchInertiaStrength();
    double pitchCorridorSafetyFraction();
    double pitchPredictionScale();
    double aimSampleMinSeconds();
    double aimSampleMaxSeconds();
    double arriveEase(double settle, double settledJitter);
    Correction correct(Rotation desired, float currentYaw, double time, boolean targetChanged);
    void reset();

    record Correction(Rotation rotation, double responseScale, double accelerationScale) { }
}
