package com.blanoir.moons.client.module.impl.combat.silentaura;

import com.blanoir.moons.client.utils.math.MathUtils;
import com.blanoir.moons.client.utils.math.RandomMath;
import com.blanoir.moons.client.utils.rotation.aim.AimParameters;
import com.blanoir.moons.client.utils.rotation.aim.AimProfile;
import com.blanoir.moons.client.utils.rotation.aim.AimProfileA;
import com.blanoir.moons.client.utils.rotation.aim.AimProfileB;
import com.blanoir.moons.client.utils.rotation.aim.AimSolverB;
import com.blanoir.moons.client.utils.rotation.aim.AimSolverC;
import com.blanoir.moons.client.utils.rotation.aim.AimSolverG;
import com.blanoir.moons.client.utils.rotation.aim.AimState;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/** Continuous rotation whose path noise fades out completely at the target. */
public final class SilentAuraRotationController {
    private final AimState state = new AimState();
    private final AimProfile aimType;
    private final boolean fullLockMode;
    private Vec3 learnedPoint;

    public SilentAuraRotationController(boolean lockMode) {
        this(lockMode, false);
    }

    SilentAuraRotationController(boolean lockMode, boolean fullLockMode) {
        this.aimType = lockMode ? new AimProfileA() : new AimProfileB();
        this.fullLockMode = fullLockMode;
    }

    public void track(Minecraft client, LivingEntity target, Vec3 point, double deltaSeconds) {
        var currentPlayer = client == null ? null : client.player;
        if (client == null || currentPlayer == null || target == null || point == null) {
            returnToCamera(client, deltaSeconds);
            return;
        }
        if (!Double.isFinite(deltaSeconds) || deltaSeconds <= 0.0D) return;
        if (!state.active) {
            state.yaw = currentPlayer.getYRot();
            state.pitch = currentPlayer.getXRot();
            state.yawVelocity = state.pitchVelocity = 0.0F;
        }
        boolean targetChanged = state.targetId != target.getId();
        if (targetChanged) {
            state.crossingTarget = false;
            state.crossingTurnDirection = 0.0F;
            state.crossingRecoveryUntilTick = Integer.MIN_VALUE;
            state.yawVelocity = state.pitchVelocity = 0.0F;
            state.pathJitterBlend = 0.0F;
            state.prediction.reset();
            state.heldOrbitOffset = null;
            state.orbitOffset = Vec3.ZERO;
            state.nextAimSampleSeconds = 0.0D;
            state.flickArmed = false;
            state.flickYawAccelScale = state.flickPitchAccelScale = 1.0D;
            state.correction.reset();
            state.motionSeed =
                    RandomMath.nextLong()
                            ^ Integer.toUnsignedLong(target.getId()) * 0xD1B54A32D192ED03L
                            ^ Integer.toUnsignedLong(currentPlayer.tickCount) * 0x9E3779B97F4A7C15L;
            state.stickyAimY = point.y;
        }
        state.active = true;
        state.returning = false;
        state.targetId = target.getId();
        learnedPoint = point;
        AimParameters parameters = parameters();
        AimSolverB.observe(state, aimType, parameters, client, target, deltaSeconds);
        if (fullLockMode) {
            AimSolverC.solve(state, parameters, client, target, point, deltaSeconds);
            return;
        }
        AimSolverB.solve(
                state, aimType, parameters, client, target, point, deltaSeconds, targetChanged);
    }

    public void returnToCamera(Minecraft client, double deltaSeconds) {
        learnedPoint = null;
        var currentPlayer = client == null ? null : client.player;
        state.targetId = -1;
        state.crossingTarget = false;
        state.yawFeedForward = 0.0D;
        state.crossingTurnDirection = 0.0F;
        if (!state.active || client == null || currentPlayer == null) {
            clear();
            return;
        }
        if (!SilentAuraConfig.returnRotation()) {
            clearAtCamera(client);
            return;
        }

        if (!state.returning) {
            state.returning = true;
            state.pathJitterBlend = 0.0F;
            state.heldOrbitOffset = null;
            state.returnMotionSeed =
                    RandomMath.nextLong()
                            ^ Integer.toUnsignedLong(currentPlayer.tickCount) * 0x94D049BB133111EBL;
            state.nextReturnMotionSample = 0.0D;
            state.correction.reset();
        }

        AimSolverG.returnTarget(state, currentPlayer.getYRot(), currentPlayer.getXRot());
        double time = System.nanoTime() * 1.0E-9D;
        AimSolverG.returnStep(state, aimType, parameters(), deltaSeconds, time);
    }

    public boolean returnPacketReached(float sentYaw, float sentPitch) {
        return state.returning
                && MathUtils.withinRotationTolerance(
                        state.returnYaw - sentYaw,
                        sentPitch - state.returnPitch,
                        AimSolverG.RETURN_DONE_ANGLE);
    }

    public void completeReturn() {
        clear();
    }

    public void cancelReturn(Minecraft client) {
        if (state.returning && client != null && client.player != null) {
            clearAtCamera(client);
        }
    }

    public boolean active() {
        return state.active;
    }

    Vec3 learnedPoint() {
        return learnedPoint;
    }

    public void beginFrom(float yaw, float pitch) {
        clear();
        state.yaw = yaw;
        state.pitch = pitch;
        state.active = true;
    }

    public boolean returning() {
        return state.returning;
    }

    public int targetId() {
        return state.targetId;
    }

    public float yaw() {
        return state.yaw;
    }

    public float pitch() {
        return state.pitch;
    }

    public boolean crossingTarget() {
        return state.crossingTarget;
    }

    public float bodyYaw() {
        return state.crossingTarget ? state.crossingBodyYaw : state.yaw;
    }

    public Vec3 lookVector() {
        return Vec3.directionFromRotation(state.pitch, state.yaw);
    }

    public void clear() {
        learnedPoint = null;
        state.active = state.returning = false;
        state.targetId = -1;
        state.yawVelocity = state.pitchVelocity = 0.0F;
        state.yawFeedForward = 0.0D;
        state.pathJitterBlend = 0.0F;
        state.motionSeed = 0L;
        state.stickyAimY = Double.NaN;
        state.heldOrbitOffset = null;
        state.orbitOffset = Vec3.ZERO;
        state.prediction.reset();
        state.noiseTime = state.noiseStrength = 0.0D;
        state.noiseSpeed = 1.0D;
        state.nextAimSampleSeconds = 0.0D;
        state.flickYawAccelScale = state.flickPitchAccelScale = 1.0D;
        state.flickArmed = false;
        state.accelNoise = 1.0D;
        state.crossingTarget = false;
        state.crossingHeadYaw = state.crossingHeadPitch = state.crossingBodyYaw = 0.0F;
        state.crossingTurnDirection = 0.0F;
        state.crossingRecoveryUntilTick = Integer.MIN_VALUE;
        state.lastTargetMinY = Double.NaN;
        state.returnYaw = state.returnPitch = 0.0F;
        state.returnMotionSeed = 0L;
        state.nextReturnMotionSample = 0.0D;
        state.returnResponseScale = state.returnYawSpeedScale = state.returnPitchSpeedScale = 1.0D;
        state.correction.reset();
    }

    private void clearAtCamera(Minecraft client) {
        // Re-base onto the camera yaw without a modulo snap: pick the 360°
        // equivalent closest to the current continuous yaw, otherwise crossing
        // the ±180 boundary emits a ~360° delta packet instead of a small turn.
        state.yaw += MathUtils.wrappedAngleDifference(state.yaw, client.player.getYRot());
        // The old implementation only rebased the controller and immediately
        // disabled it. The following vanilla packet therefore still used the
        // camera's wrapped numeric yaw (for example -179 after a sent 181),
        // producing a -360 degree delta. Preserve the visually identical
        // continuous equivalent on the actual player before releasing control.
        client.player.setYRot(state.yaw);
        state.pitch = client.player.getXRot();
        clear();
    }

    private static AimParameters parameters() {
        return new AimParameters(
                SilentAuraConfig.fullLockPrediction(),
                SilentAuraConfig.jitter(),
                SilentAuraConfig.jitterSpeed(),
                SilentAuraConfig.matrixCompatibility(),
                SilentAuraConfig.motionPredictionParameters(),
                SilentAuraConfig.prediction(),
                SilentAuraConfig.predictionLead(),
                SilentAuraConfig.returnSmooth(),
                SilentAuraConfig.settledJitter(),
                SilentAuraConfig.smooth(),
                SilentAuraConfig.throughBlocks());
    }
}
