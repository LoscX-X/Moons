package com.blanoir.moons.client.utils.rotation.aim;

import com.blanoir.moons.client.utils.rotation.Rotation;

/** Mode-specific SilentAura rotation behavior. */
public interface AimProfile {
    boolean lock();

    double sampleFlickYawScale();

    double sampleFlickPitchScale();

    double jitterScale();

    double response(double smooth);

    double maxYawSpeed();

    double maxPitchSpeed();

    double yawAssistScale();

    double pitchAssistScale();

    double pitchInertiaStrength();

    double pitchCorridorSafetyFraction();

    double pitchPredictionScale();

    double aimSampleMinSeconds();

    double aimSampleMaxSeconds();

    double arriveEase(double settle, double settledJitter);

    Correction correct(
            State state, Rotation desired, float currentYaw, double time, boolean targetChanged);

    /** Correction history belongs to the mode, including the original lazy seed initialization. */
    final class State {
        public long seed;
        public boolean initialized;

        public void reset() {
            initialized = false;
            seed = 0L;
        }
    }

    record Correction(Rotation rotation, double responseScale, double accelerationScale) {}
}
