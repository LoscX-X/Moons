package com.blanoir.moons.client.utils.rotation.aim;

import com.blanoir.moons.client.utils.prediction.MotionPrediction;

/** Immutable configuration snapshot shared by the tracking, FullLock and return solvers.
 * The mode reads settings in the original order; no algorithm owns another solver's inputs. */
public record AimParameters(
        double fullLockPrediction,
        double jitter,
        double jitterSpeed,
        boolean matrixCompatibility,
        MotionPrediction.Parameters motionPredictionParameters,
        double prediction,
        double predictionLead,
        double returnSmooth,
        double settledJitter,
        double smooth,
        boolean throughBlocks) {}
