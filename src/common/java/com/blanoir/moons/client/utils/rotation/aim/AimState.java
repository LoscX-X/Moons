package com.blanoir.moons.client.utils.rotation.aim;

import com.blanoir.moons.client.utils.prediction.MotionPrediction;

import net.minecraft.world.phys.Vec3;

/** Mutable history owned by one mode/controller. Utilities never retain or share this state. */
public final class AimState {
    public boolean active;
    public boolean returning;
    public int targetId = -1;
    public float yaw;
    public float pitch;
    public float yawVelocity;
    public float pitchVelocity;
    public double yawFeedForward;
    public float pathJitterBlend;
    public long motionSeed;
    public double stickyAimY = Double.NaN;
    public Vec3 heldOrbitOffset;
    public Vec3 orbitOffset = Vec3.ZERO;
    public MotionPrediction prediction = new MotionPrediction();
    public double noiseTime;
    public double noiseStrength;
    public double noiseSpeed = 1.0D;
    public double nextAimSampleSeconds;
    public double flickYawAccelScale = 1.0D;
    public double flickPitchAccelScale = 1.0D;
    public boolean flickArmed;
    public double accelNoise = 1.0D;
    public boolean crossingTarget;
    public float crossingHeadYaw;
    public float crossingHeadPitch;
    public float crossingBodyYaw;
    public float crossingTurnDirection;
    public int crossingRecoveryUntilTick = Integer.MIN_VALUE;
    public double lastTargetMinY = Double.NaN;
    public float returnYaw;
    public float returnPitch;
    public long returnMotionSeed;
    public double nextReturnMotionSample;
    public double returnResponseScale = 1.0D;
    public double returnYawSpeedScale = 1.0D;
    public double returnPitchSpeedScale = 1.0D;
    public final AimProfile.State correction = new AimProfile.State();
}
