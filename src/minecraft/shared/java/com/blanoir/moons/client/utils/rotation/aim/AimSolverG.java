package com.blanoir.moons.client.utils.rotation.aim;

import com.blanoir.moons.client.utils.math.MathUtils;
import com.blanoir.moons.client.utils.rotation.Rotation;

import net.minecraft.util.Mth;

/**
 * G: Camera-return angle solution, separate from target tracking. Preserves the original
 * return sampling schedule, noise curve and continuous camera target in caller-owned state.
 * The mode decides when return begins/ends, supplies time and performs resets; AimStepA
 * applies the same pursuit integrator used during tracking.
 */
public final class AimSolverG {
    private AimSolverG() {}

    public static final float RETURN_DONE_ANGLE = 0.35F;

    public static void returnStep(
            AimState state,
            AimProfile profile,
            AimParameters parameters,
            double deltaSeconds,
            double time) {
        if (time >= state.nextReturnMotionSample) {
            // Return corrections happen in short hand-like bursts instead of
            // repeating one exact exponential acceleration for the whole arc.
            state.returnResponseScale = AimSamplingA.sampleVariation(0.86D, 1.15D);
            state.returnYawSpeedScale = AimSamplingA.sampleVariation(0.82D, 1.18D);
            state.returnPitchSpeedScale = AimSamplingA.sampleVariation(0.88D, 1.12D);
            state.nextReturnMotionSample = time + AimSamplingA.sampleVariation(0.045D, 0.110D);
        }
        Rotation exactReturn = new Rotation(state.returnYaw, state.returnPitch);
        double remaining = MathUtils.angularDistance(state.yaw, state.pitch, exactReturn);
        double variationBlend =
                Mth.clamp((remaining - RETURN_DONE_ANGLE * 1.8D) / 18.0D, 0.0D, 1.0D);
        double yawCurve =
                AimNoiseB.sample(time * 1.73D, state.returnMotionSeed)
                        * Math.min(0.65D, remaining * 0.018D)
                        * variationBlend;
        double pitchCurve =
                AimNoiseB.sample(time * 1.31D, state.returnMotionSeed ^ 0x9E3779B97F4A7C15L)
                        * Math.min(0.22D, remaining * 0.007D)
                        * variationBlend;
        double responseFlow =
                AimNoiseB.sample(time * 3.17D, state.returnMotionSeed ^ 0xBF58476D1CE4E5B9L)
                                * 0.13D
                                * variationBlend
                        + 1.0D;
        double smooth = parameters.returnSmooth();
        AimStepA.stepToward(
                state,
                profile,
                new Rotation(
                        state.returnYaw + (float) yawCurve, state.returnPitch + (float) pitchCurve),
                deltaSeconds,
                (5.0D + smooth * 18.0D) * state.returnResponseScale * responseFlow,
                (300.0D + smooth * 600.0D) * state.returnYawSpeedScale,
                (220.0D + smooth * 420.0D) * state.returnPitchSpeedScale,
                1.0D,
                1.0D);
    }

    public static void returnTarget(AimState state, float cameraYaw, float cameraPitch) {
        state.returnYaw = state.yaw + MathUtils.wrappedAngleDifference(state.yaw, cameraYaw);
        state.returnPitch = cameraPitch;
    }
}
