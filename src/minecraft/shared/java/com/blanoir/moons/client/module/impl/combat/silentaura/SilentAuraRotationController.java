package com.blanoir.moons.client.module.impl.combat.silentaura;

import static com.blanoir.moons.client.utils.math.MathUtils.approach;
import static com.blanoir.moons.client.utils.math.MathUtils.approachWrapped;

import com.blanoir.moons.client.module.impl.combat.silentaura.aim.BalanceSilentAimType;
import com.blanoir.moons.client.module.impl.combat.silentaura.aim.LockSilentAimType;
import com.blanoir.moons.client.module.impl.combat.silentaura.aim.SilentAimType;
import com.blanoir.moons.client.utils.math.MathUtils;
import com.blanoir.moons.client.utils.math.RandomMath;
import com.blanoir.moons.client.utils.math.Smoothing;
import com.blanoir.moons.client.utils.prediction.AimPrediction;
import com.blanoir.moons.client.utils.prediction.MotionPrediction;
import com.blanoir.moons.client.utils.prediction.TrajectoryPrediction;
import com.blanoir.moons.client.utils.raytrace.RaytraceUtils;
import com.blanoir.moons.client.utils.rotation.Rotation;
import com.blanoir.moons.client.utils.rotation.aim.AimJitter;
import com.blanoir.moons.client.utils.rotation.aim.AimMotionNoise;
import com.blanoir.moons.client.utils.rotation.aim.HumanAimSimulator;
import com.blanoir.moons.client.utils.rotation.aim.RotationUtils;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Continuous rotation whose path noise fades out completely at the target. */
public final class SilentAuraRotationController {
    /** Ignore sub-GCD drift instead of emitting a servo correction every frame. */
    private static final float ANGLE_DEADZONE = 0.075F;

    /** Ignore insignificant changes to the target-local noise offset. */
    private static final double ORBIT_OFFSET_DEADZONE = 0.002D;

    /** Enter slightly before the local horizontal centre reaches the box, then leave through a wider boundary to prevent edge chatter. */
    private static final double CROSSING_ENTER_MARGIN = 0.025D;

    private static final double CROSSING_EXIT_MARGIN = 0.22D;

    /** Prefer chest/head, but keep a small safe inset when only the lower body is visible. */
    private static final double LOWER_BODY_FLOOR = 0.03D;

    private static final double UPPER_BODY_FLOOR = 0.56D;
    private static final double UPPER_BODY_CEILING = 0.92D;

    /** Keeps a jump from dragging the aim point through the full body height. */
    private static final double AIRBORNE_AIM_HEIGHT_BLEND = 0.42D;

    private static final float RETURN_DONE_ANGLE = 0.35F;

    private boolean active;
    private boolean returning;
    private int targetId = -1;
    private float yaw;
    private float pitch;
    private float yawVelocity;
    private float pitchVelocity;
    private float pathJitterBlend;
    private long motionSeed;
    private double stickyAimY = Double.NaN;
    private Vec3 heldOrbitOffset;
    private Vec3 orbitOffset = Vec3.ZERO;
    private MotionPrediction prediction = new MotionPrediction();
    private double noiseTime;
    private double noiseStrength;
    private double noiseSpeed = 1.0D;
    private double nextAimSampleSeconds;
    private double flickYawAccelScale = 1.0D;
    private double flickPitchAccelScale = 1.0D;
    private boolean flickArmed;
    private double accelNoise = 1.0D;
    private boolean crossingTarget;
    private float crossingHeadYaw;
    private float crossingHeadPitch;
    private float crossingBodyYaw;
    private int crossingRecoveryUntilTick = Integer.MIN_VALUE;
    private double lastTargetMinY = Double.NaN;
    private float returnYaw;
    private float returnPitch;
    private long returnMotionSeed;
    private double nextReturnMotionSample;
    private double returnResponseScale = 1.0D;
    private double returnYawSpeedScale = 1.0D;
    private double returnPitchSpeedScale = 1.0D;
    private final SilentAimType aimType;
    private final boolean fullLockMode;

    public SilentAuraRotationController(boolean lockMode) {
        this(lockMode, false);
    }

    SilentAuraRotationController(boolean lockMode, boolean fullLockMode) {
        this.aimType = lockMode ? new LockSilentAimType() : new BalanceSilentAimType();
        this.fullLockMode = fullLockMode;
    }

    public void track(Minecraft client, LivingEntity target, Vec3 point, double deltaSeconds) {
        var currentPlayer = client == null ? null : client.player;
        if (client == null || currentPlayer == null || target == null || point == null) {
            returnToCamera(client, deltaSeconds);
            return;
        }
        if (!Double.isFinite(deltaSeconds) || deltaSeconds <= 0.0D) return;
        if (!active) {
            yaw = currentPlayer.getYRot();
            pitch = currentPlayer.getXRot();
            yawVelocity = pitchVelocity = 0.0F;
        }
        boolean targetChanged = targetId != target.getId();
        if (targetChanged) {
            crossingTarget = false;
            crossingRecoveryUntilTick = Integer.MIN_VALUE;
            yawVelocity = pitchVelocity = 0.0F;
            pathJitterBlend = 0.0F;
            prediction.reset();
            heldOrbitOffset = null;
            orbitOffset = Vec3.ZERO;
            nextAimSampleSeconds = 0.0D;
            flickArmed = false;
            flickYawAccelScale = flickPitchAccelScale = 1.0D;
            aimType.reset();
            motionSeed =
                    RandomMath.nextLong()
                            ^ Integer.toUnsignedLong(target.getId()) * 0xD1B54A32D192ED03L
                            ^ Integer.toUnsignedLong(currentPlayer.tickCount) * 0x9E3779B97F4A7C15L;
            stickyAimY = point.y;
        }
        active = true;
        returning = false;
        targetId = target.getId();
        MotionPrediction.Parameters predictionParameters =
                SilentAuraConfig.motionPredictionParameters();
        if (!prediction.parameters().equals(predictionParameters)) {
            prediction = new MotionPrediction(predictionParameters);
        }
        prediction.observe(currentPlayer.tickCount, target.position(), target.getDeltaMovement());
        double frameDelta = Math.max(0.0D, Math.min(0.05D, deltaSeconds));
        double parameterBlend = Smoothing.exponentialResponse(12.0D, frameDelta);
        noiseStrength += (SilentAuraConfig.jitter() - noiseStrength) * parameterBlend;
        noiseSpeed += (SilentAuraConfig.jitterSpeed() - noiseSpeed) * parameterBlend;
        noiseTime += frameDelta * noiseSpeed;

        if (fullLockMode) {
            trackFullLock(client, target, point, deltaSeconds);
            return;
        }

        double time = noiseTime;
        Vec3 eye = currentPlayer.getEyePosition();
        AABB targetBox = target.getBoundingBox();
        double bodyFloorFraction = bodyFloorFraction(point, targetBox);
        boolean lowerBodyFallback = bodyFloorFraction < UPPER_BODY_FLOOR;
        boolean enteringCrossing =
                !crossingTarget
                        && occupiesCrossingCorridor(client, eye, targetBox, CROSSING_ENTER_MARGIN);
        if (enteringCrossing) {
            crossingTarget = true;
            crossingHeadYaw = yaw;
            crossingHeadPitch = pitch;
            crossingBodyYaw = yaw;
            yawVelocity = pitchVelocity = 0.0F;
            heldOrbitOffset = null;
        } else if (crossingTarget
                && !occupiesCrossingCorridor(client, eye, targetBox, CROSSING_EXIT_MARGIN)) {
            crossingTarget = false;
            // The airborne corridor was introduced to avoid needless pitch
            // corrections while a valid ray remains inside the body. Once we
            // have crossed out of that body it must release immediately;
            // otherwise a long landing keeps the pre-crossing pitch and the
            // attack gate appears to stall for several ticks.
            crossingRecoveryUntilTick = currentPlayer.tickCount + 2;
            pathJitterBlend = 0.0F;
            heldOrbitOffset = null;
        }

        if (crossingTarget) {
            // A point inside an AABB has no stable look direction. As the eye
            // crosses its centre, atan2 changes side and the computed pitch can
            // dive toward the feet for one frame. Track only the stable
            // horizontal direction while inside the box. The old code froze
            // yaw and pitch at their entry values; repeated look packets with
            // that exact pair are what ACA EqualRotation checks directly.
            Vec3 centre = targetBox.getCenter();
            double lookahead =
                    AimPrediction.crossingLookaheadTicks(
                            currentPlayer.getDeltaMovement(),
                            prediction.velocity(),
                            eye,
                            targetBox,
                            CROSSING_EXIT_MARGIN);
            Vec3 localVelocity = currentPlayer.getDeltaMovement();
            Vec3 targetVelocity = prediction.velocity();
            Vec3 yawEye =
                    lookahead <= 0.0D
                            ? eye
                            : eye.add(
                                    localVelocity.x * lookahead, 0.0D, localVelocity.z * lookahead);
            Vec3 yawCentre =
                    lookahead <= 0.0D
                            ? centre
                            : centre.add(
                                    targetVelocity.x * lookahead,
                                    0.0D,
                                    targetVelocity.z * lookahead);
            double horizontalSquared =
                    Mth.square(yawCentre.x - yawEye.x) + Mth.square(yawCentre.z - yawEye.z);
            if (horizontalSquared > 1.0E-4D) {
                crossingBodyYaw =
                        RotationUtils.rotationTo(
                                        yawEye, new Vec3(yawCentre.x, yawEye.y, yawCentre.z))
                                .yaw();
            }
            double crossingDelta = Mth.clamp(deltaSeconds, 0.0D, 1.0D / 20.0D);
            // Keep the 20 TPS target step below the consecutive >20
            // degree snap threshold. The packet smoother applies an 18-degree
            // backstop in case render sampling carries momentum into the box.
            boolean matrixProfile = SilentAuraConfig.matrixCompatibility();
            double crossingYawRate =
                    matrixProfile
                            ? (aimType.lock() ? 320.0D : 220.0D)
                            : (aimType.lock() ? 900.0D : 620.0D);
            float yawLimit = (float) (crossingYawRate * crossingDelta);
            float previousCrossingYaw = crossingHeadYaw;
            float previousCrossingPitch = crossingHeadPitch;
            crossingHeadYaw = approachWrapped(crossingHeadYaw, crossingBodyYaw, yawLimit);
            boolean eyeInsideTarget = targetBox.inflate(CROSSING_ENTER_MARGIN).contains(eye);
            // Inside the body, preserve valid pitch. Above it, use actual exit
            // geometry; a fixed 2-5 degree yaw-coupled pitch had no geometric basis.
            float crossingPitchTarget =
                    eyeInsideTarget
                            ? crossingHeadPitch
                            : RotationUtils.rotationTo(
                                            yawEye, new Vec3(yawCentre.x, point.y, yawCentre.z))
                                    .pitch();
            double crossingPitchRate =
                    matrixProfile
                            ? (aimType.lock() ? 18.0D : 14.0D)
                            : (aimType.lock() ? 360.0D : 260.0D);
            float pitchLimit = (float) (crossingPitchRate * crossingDelta);
            crossingHeadPitch = approach(crossingHeadPitch, crossingPitchTarget, pitchLimit);
            yaw = crossingHeadYaw;
            pitch = crossingHeadPitch;
            // Preserve finite velocities for the exit frame instead of
            // restarting a turn from zero at the wider crossing boundary.
            yawVelocity =
                    MathUtils.wrappedAngleDifference(previousCrossingYaw, yaw)
                            / (float) crossingDelta;
            pitchVelocity = (pitch - previousCrossingPitch) / (float) crossingDelta;
            pathJitterBlend =
                    approach(
                            pathJitterBlend,
                            0.0F,
                            (float) (Mth.clamp(deltaSeconds, 0.0D, 0.05D) * 12.0D));
            return;
        }

        Rotation baseRotation = RotationUtils.rotationTo(eye, point);
        double remaining = MathUtils.angularDistance(yaw, pitch, baseRotation);
        // A new flick gets its own muscle strength; reusing one scale across
        // turns makes every turn's acceleration ramp identical.
        if (remaining > 8.0D && !flickArmed) {
            flickArmed = true;
            flickYawAccelScale = aimType.sampleFlickYawScale();
            flickPitchAccelScale = aimType.sampleFlickPitchScale();
        } else if (remaining < 2.0D) {
            flickArmed = false;
        }
        accelNoise =
                1.0D
                        + AimMotionNoise.sample(time * 3.7D, motionSeed)
                                * (aimType.lock() ? 0.04D : 0.10D)
                                * noiseStrength;
        double angularSpeed = Math.hypot(yawVelocity, pitchVelocity);
        float pathDemand =
                (float)
                        Math.max(
                                Mth.clamp((remaining - 0.35D) / 6.0D, 0.0D, 1.0D),
                                Mth.clamp(angularSpeed / 260.0D, 0.0D, 0.75D));
        double blendRate = pathDemand > pathJitterBlend ? 5.5D : 9.0D;
        pathJitterBlend =
                approach(
                        pathJitterBlend,
                        pathDemand,
                        (float) (Mth.clamp(deltaSeconds, 0.0D, 0.05D) * blendRate));
        // Preserve a small settled orbit instead of freezing on one point.
        // Full jitter is reserved for the active turn path.
        double effectiveJitter =
                Mth.clamp(noiseStrength * (0.28D + pathJitterBlend * 0.72D), 0.0D, 1.0D);
        effectiveJitter *= aimType.jitterScale();
        // A lower-body point means the selector could not see the preferred
        // upper region. Keep that exact exposed opening instead of wandering
        // the point back behind the block.
        if (lowerBodyFallback) effectiveJitter = 0.0D;
        Vec3 jitteredPoint =
                AimJitter.insideHitbox(
                        eye,
                        point,
                        target.getBoundingBox(),
                        time,
                        motionSeed,
                        effectiveJitter,
                        1.0D);
        if (!aimType.lock()) {
            // Balance humanizes yaw/depth only. Pitch must represent actual
            // geometry, not a noise channel, otherwise a level target causes
            // needless head lifts even though the horizontal ray is valid.
            jitteredPoint = new Vec3(jitteredPoint.x, point.y, jitteredPoint.z);
        }
        Vec3 anchoredPoint =
                stabilizeAimHeight(
                        target, jitteredPoint, deltaSeconds, targetChanged, bodyFloorFraction);
        Vec3 movingPoint =
                leadAimPoint(
                        client, target, anchoredPoint, deltaSeconds, aimType, lowerBodyFallback);
        double assist = accelerationAssist(client, target, movingPoint);
        double jitter = effectiveJitter;
        long seed = motionSeed;
        double responseVariation =
                1.0D + AimMotionNoise.sample(time * 0.91D, seed) * 0.12D * jitter;
        double yawAccelerationVariation =
                1.0D
                        + AimMotionNoise.sample(time * 2.15D, seed ^ 0x9E3779B97F4A7C15L)
                                * 0.28D
                                * jitter;
        double pitchAccelerationVariation =
                1.0D
                        + AimMotionNoise.sample(time * 1.87D, seed ^ 0x94D049BB133111EBL)
                                * 0.25D
                                * jitter;
        // Lock is the combat-first profile: substantially higher response and
        // acceleration, almost no settled orbit, and a live point every frame.
        // Balance keeps the softer humanized path.
        // A fallback point represents the small part of the hitbox that is
        // actually exposed. Reach it more decisively: the normal soft pursuit
        // is useful on an open body, but can spend most of the attack window
        // travelling from a covered chest anchor into a narrow leg opening.
        double visibilityUrgency = lowerBodyFallback ? 1.30D : 1.0D;
        double response =
                aimType.response(SilentAuraConfig.smooth()) * responseVariation * visibilityUrgency;
        // Once the crosshair has mostly arrived, stop converging onto one fixed
        // point: the orbit keeps the settled aim drifting up/down and left/right
        // inside the hitbox, and the softened response lets it overshoot and
        // correct like a hand instead of snapping on a straight spring path.
        // Only the small target-local offset is sampled; geometry stays live.
        double settle = Mth.clamp(1.0D - remaining / 2.2D, 0.0D, 1.0D);
        // The pursuit base (movingPoint) stays live every frame so sent rays
        // keep intersecting a strafing target; only the humanizing orbit
        // offset is held in bursts. The deadzone on the offset keeps the
        // micro-drift from degenerating into a per-frame servo oscillation.
        Vec3 orbitCandidate =
                aimType.lock() || lowerBodyFallback
                        ? movingPoint
                        : settledOrbit(
                                eye,
                                point,
                                movingPoint,
                                target,
                                time,
                                settle * (0.25D + 0.75D * pathJitterBlend));
        Vec3 offsetCandidate = orbitCandidate.subtract(movingPoint);
        if (lowerBodyFallback) {
            heldOrbitOffset = Vec3.ZERO;
            nextAimSampleSeconds = time;
        } else if (targetChanged || heldOrbitOffset == null || time >= nextAimSampleSeconds) {
            if (targetChanged
                    || heldOrbitOffset == null
                    || offsetCandidate.subtract(heldOrbitOffset).lengthSqr()
                            > ORBIT_OFFSET_DEADZONE * ORBIT_OFFSET_DEADZONE) {
                heldOrbitOffset = offsetCandidate;
            }
            nextAimSampleSeconds =
                    time
                            + RandomMath.between(
                                    aimType.aimSampleMinSeconds(), aimType.aimSampleMaxSeconds());
        }
        orbitOffset =
                lowerBodyFallback
                        ? Vec3.ZERO
                        : orbitOffset.lerp(
                                heldOrbitOffset, Smoothing.exponentialResponse(16.0D, frameDelta));
        Vec3 desiredPoint = movingPoint.add(orbitOffset);
        AABB aimBox = target.getBoundingBox();
        double insetX = lowerBodyFallback ? 0.002D : Math.min(aimBox.getXsize() * 0.12D, 0.08D);
        double insetZ = lowerBodyFallback ? 0.002D : Math.min(aimBox.getZsize() * 0.12D, 0.08D);
        double bodyFloor = Mth.lerp(bodyFloorFraction, aimBox.minY, aimBox.maxY);
        double upperBodyCeiling = Mth.lerp(UPPER_BODY_CEILING, aimBox.minY, aimBox.maxY);
        boolean localAirborne =
                !currentPlayer.onGround() && Math.abs(currentPlayer.getDeltaMovement().y) > 0.012D;
        double desiredY = desiredPoint.y;
        if (localAirborne && !lowerBodyFallback) {
            // Stay inside the same valid hitbox while choosing a height closer
            // to our moving eye. This reduces needless head nodding during our
            // own jump without weakening the pitch needed to keep the ray valid.
            double calmY = Mth.clamp(eye.y, bodyFloor, upperBodyCeiling);
            desiredY = Mth.lerp(AIRBORNE_AIM_HEIGHT_BLEND, desiredY, calmY);
        }
        desiredPoint =
                new Vec3(
                        Mth.clamp(desiredPoint.x, aimBox.minX + insetX, aimBox.maxX - insetX),
                        Mth.clamp(desiredY, bodyFloor, upperBodyCeiling),
                        Mth.clamp(desiredPoint.z, aimBox.minZ + insetZ, aimBox.maxZ - insetZ));
        if (!RaytraceUtils.canRayTraceTo(client, eye, desiredPoint)) {
            // Prediction/jitter can move an originally visible top-edge point
            // behind the ledge when attacking downward. Recover the selector's
            // ray and inset it only as far as visibility permits, giving the
            // quantized attack ray real hitbox margin without changing motion.
            desiredPoint = deepestVisibleInset(client, eye, point, aimBox);
        }
        double arriveEase = aimType.arriveEase(settle, SilentAuraConfig.settledJitter());
        Rotation desiredRotation = RotationUtils.rotationTo(eye, desiredPoint);
        SilentAimType.Correction correction =
                aimType.correct(desiredRotation, yaw, time, targetChanged);
        desiredRotation = correction.rotation();
        desiredRotation =
                applyAirbornePitchInertia(
                        client, target, eye, desiredPoint, desiredRotation, lowerBodyFallback);
        stepToward(
                desiredRotation,
                deltaSeconds,
                response * arriveEase * correction.responseScale(),
                aimType.maxYawSpeed(),
                aimType.maxPitchSpeed(),
                accelNoise
                        * flickYawAccelScale
                        * visibilityUrgency
                        * (1.0D + assist * aimType.yawAssistScale())
                        * yawAccelerationVariation
                        * correction.accelerationScale(),
                accelNoise
                        * flickPitchAccelScale
                        * visibilityUrgency
                        * (1.0D + assist * aimType.pitchAssistScale())
                        * pitchAccelerationVariation
                        * correction.accelerationScale());
    }

    /** Leaves packet-cadence angle stepping to the FULL-Lock profile. */
    private void trackFullLock(Minecraft client, LivingEntity target, Vec3 point, double rawDelta) {
        double delta = Mth.clamp(rawDelta, 1.0D / 1000.0D, 1.0D / 20.0D);
        float previousYaw = yaw;
        float previousPitch = pitch;
        Vec3 leadPoint = fullLockLeadPoint(client, target, point, delta);
        Rotation desired = RotationUtils.rotationTo(client.player.getEyePosition(), leadPoint);
        yaw += MathUtils.wrappedAngleDifference(yaw, desired.yaw());
        pitch = Mth.clamp(desired.pitch(), -90.0F, 90.0F);
        yawVelocity = MathUtils.wrappedAngleDifference(previousYaw, yaw) / (float) delta;
        pitchVelocity = (pitch - previousPitch) / (float) delta;
        crossingTarget = false;
        pathJitterBlend = 0.0F;
        heldOrbitOffset = null;
    }

    /**
     * FULL-Lock prediction is deliberately independent from the humanized
     * Lock/Balance predictor. Lead is kept inside the entity's current box so
     * the final packet ray can still prove a real HIT; it shifts the lock
     * toward the moving half of the body instead of aiming at future air.
     */
    private Vec3 fullLockLeadPoint(
            Minecraft client, LivingEntity target, Vec3 point, double deltaSeconds) {
        double leadTicks = SilentAuraConfig.fullLockPrediction();
        if (leadTicks <= 0.0D) return point;
        Vec3 travel = prediction.displacement(leadTicks);

        AABB box = target.getBoundingBox();
        // Preserve angular room for quantization at the outer edge of reach.
        double insetX = Math.min(box.getXsize() * 0.22D, 0.11D);
        double insetZ = Math.min(box.getZsize() * 0.22D, 0.11D);
        Vec3 predicted =
                AimPrediction.clampedLead(
                        point,
                        new Vec3(travel.x, 0.0D, travel.z),
                        new AABB(
                                box.minX + insetX,
                                point.y,
                                box.minZ + insetZ,
                                box.maxX - insetX,
                                point.y,
                                box.maxZ - insetZ));
        Vec3 eye = client.player.getEyePosition();
        return RaytraceUtils.canRayTraceTo(client, eye, predicted) ? predicted : point;
    }

    public void returnToCamera(Minecraft client, double deltaSeconds) {
        var currentPlayer = client == null ? null : client.player;
        targetId = -1;
        crossingTarget = false;
        if (!active || client == null || currentPlayer == null) {
            clear();
            return;
        }
        if (!SilentAuraConfig.returnRotation()) {
            clearAtCamera(client);
            return;
        }

        if (!returning) {
            returning = true;
            pathJitterBlend = 0.0F;
            heldOrbitOffset = null;
            returnMotionSeed =
                    RandomMath.nextLong()
                            ^ Integer.toUnsignedLong(currentPlayer.tickCount) * 0x94D049BB133111EBL;
            nextReturnMotionSample = 0.0D;
            aimType.reset();
        }

        // Keep the target yaw in the same continuous 360-degree domain as the
        // last silent yaw. This prevents the return path from crossing the
        // +/-180 boundary as a synthetic full turn.
        returnYaw = yaw + MathUtils.wrappedAngleDifference(yaw, currentPlayer.getYRot());
        returnPitch = currentPlayer.getXRot();
        double time = System.nanoTime() * 1.0E-9D;
        if (time >= nextReturnMotionSample) {
            // Return corrections happen in short hand-like bursts instead of
            // repeating one exact exponential acceleration for the whole arc.
            returnResponseScale = HumanAimSimulator.sampleVariation(0.86D, 1.15D);
            returnYawSpeedScale = HumanAimSimulator.sampleVariation(0.82D, 1.18D);
            returnPitchSpeedScale = HumanAimSimulator.sampleVariation(0.88D, 1.12D);
            nextReturnMotionSample = time + HumanAimSimulator.sampleVariation(0.045D, 0.110D);
        }
        Rotation exactReturn = new Rotation(returnYaw, returnPitch);
        double remaining = MathUtils.angularDistance(yaw, pitch, exactReturn);
        double variationBlend =
                Mth.clamp((remaining - RETURN_DONE_ANGLE * 1.8D) / 18.0D, 0.0D, 1.0D);
        double yawCurve =
                AimMotionNoise.sample(time * 1.73D, returnMotionSeed)
                        * Math.min(0.65D, remaining * 0.018D)
                        * variationBlend;
        double pitchCurve =
                AimMotionNoise.sample(time * 1.31D, returnMotionSeed ^ 0x9E3779B97F4A7C15L)
                        * Math.min(0.22D, remaining * 0.007D)
                        * variationBlend;
        double responseFlow =
                AimMotionNoise.sample(time * 3.17D, returnMotionSeed ^ 0xBF58476D1CE4E5B9L)
                                * 0.13D
                                * variationBlend
                        + 1.0D;
        double smooth = SilentAuraConfig.returnSmooth();
        stepToward(
                new Rotation(returnYaw + (float) yawCurve, returnPitch + (float) pitchCurve),
                deltaSeconds,
                (5.0D + smooth * 18.0D) * returnResponseScale * responseFlow,
                (300.0D + smooth * 600.0D) * returnYawSpeedScale,
                (220.0D + smooth * 420.0D) * returnPitchSpeedScale,
                1.0D,
                1.0D);
    }

    public boolean returnPacketReached(float sentYaw, float sentPitch) {
        return returning
                && Math.abs(MathUtils.wrappedAngleDifference(sentYaw, returnYaw))
                        <= RETURN_DONE_ANGLE
                && Math.abs(sentPitch - returnPitch) <= RETURN_DONE_ANGLE;
    }

    public void completeReturn() {
        clear();
    }

    public void cancelReturn(Minecraft client) {
        if (returning && client != null && client.player != null) {
            clearAtCamera(client);
        }
    }

    /** Limits the current angle error with a varied frame-time step cap. */
    private void stepToward(
            Rotation desired,
            double rawDelta,
            double response,
            double maxYawSpeed,
            double maxPitchSpeed,
            double yawStepScale,
            double pitchStepScale) {
        double delta = Mth.clamp(rawDelta, 0.0D, 1.0D / 20.0D);
        if (delta <= 0.0D) return;
        float yawDifference = MathUtils.wrappedAngleDifference(yaw, desired.yaw());
        float pitchDifference = desired.pitch() - pitch;
        double responseFraction = Smoothing.exponentialResponse(Math.max(0.01D, response), delta);
        double yawCap = maxYawSpeed * delta * Mth.clamp(yawStepScale, 0.62D, 1.42D);
        double pitchCap = maxPitchSpeed * delta * Mth.clamp(pitchStepScale, 0.62D, 1.42D);
        float yawStep =
                Math.abs(yawDifference) <= ANGLE_DEADZONE
                        ? 0.0F
                        : (float) Mth.clamp(yawDifference * responseFraction, -yawCap, yawCap);
        float pitchStep =
                Math.abs(pitchDifference) <= ANGLE_DEADZONE
                        ? 0.0F
                        : (float)
                                Mth.clamp(pitchDifference * responseFraction, -pitchCap, pitchCap);
        if (!aimType.lock() && !returning) {
            // Balance trades acquisition speed for a finite acceleration ramp.
            // Integrate velocity instead of instantly replacing it with error * gain.
            float nextYawVelocity =
                    approach(
                            yawVelocity,
                            yawStep / (float) delta,
                            (float) (3_600.0D * yawStepScale * delta));
            float nextPitchVelocity =
                    approach(
                            pitchVelocity,
                            pitchStep / (float) delta,
                            (float) (2_600.0D * pitchStepScale * delta));
            yawStep = (yawVelocity + nextYawVelocity) * 0.5F * (float) delta;
            pitchStep = (pitchVelocity + nextPitchVelocity) * 0.5F * (float) delta;
            yawVelocity = nextYawVelocity;
            pitchVelocity = nextPitchVelocity;
        } else {
            yawVelocity = yawStep / (float) delta;
            pitchVelocity = pitchStep / (float) delta;
        }
        // Keep yaw continuous like vanilla instead of wrapping it to ±180:
        // a wrapped yaw snaps ~360° when crossing the boundary after a run of
        // small steps, which server-side checks interpret as a modulo artifact.
        // All internal differences already wrap, so tracking is unchanged.
        yaw += yawStep;
        pitch = Mth.clamp(pitch + pitchStep, -90.0F, 90.0F);
    }

    /** Prediction affects turning acceleration only; it never moves the hitbox target point. */
    private double accelerationAssist(Minecraft client, LivingEntity target, Vec3 point) {
        if (SilentAuraConfig.prediction() <= 0.0D) return 0.0D;
        // The eye position already contains local-player movement. Subtracting
        // our velocity here counts circling twice and creates a turn-rate boost
        // perfectly synchronized with strafing around a stationary target.
        Vec3 relative = prediction.velocity();
        double distance = Math.max(client.player.getEyePosition().distanceTo(point), 0.25D);
        double angularDemand = Math.toDegrees(relative.length() / distance);
        return Mth.clamp(angularDemand / 8.0D * SilentAuraConfig.prediction(), 0.0D, 1.0D);
    }

    /**
     * The opponent's predicted velocity becomes an aim weight: instead of only
     * boosting acceleration, the smoothed relative velocity pulls the aim point
     * ahead of the target's current position, so tracking a strafing player
     * curves toward where the body is heading instead of chasing it in a line.
     */
    private Vec3 leadAimPoint(
            Minecraft ignoredClient,
            LivingEntity target,
            Vec3 point,
            double deltaSeconds,
            SilentAimType aimType,
            boolean lowerBodyFallback) {
        double leadTicks =
                SilentAuraConfig.predictionLead()
                        * SilentAuraConfig.prediction()
                        * (aimType.lock() ? 1.15D : 0.85D);
        // Prediction is useful on an open hitbox, but when only a leg-sized
        // opening is exposed it can move an otherwise visible point behind the
        // cover. The live selector already follows the moving entity.
        if (leadTicks <= 0.0D || lowerBodyFallback) return point;
        AABB box = target.getBoundingBox();
        double upperBodyFloor = Mth.lerp(UPPER_BODY_FLOOR, box.minY, box.maxY);
        double upperBodyCeiling = Mth.lerp(UPPER_BODY_CEILING, box.minY, box.maxY);
        return AimPrediction.clampedLead(
                point,
                prediction.displacement(leadTicks),
                new AABB(box.minX, upperBodyFloor, box.minZ, box.maxX, upperBodyCeiling, box.maxZ));
    }

    /**
     * Slow horizontal elliptical drift around the aim point while settled.
     * Pitch stays on the selector's visible height; vertical orbit used to
     * raise/lower the head even when a level ray already crossed the hitbox.
     */
    private Vec3 settledOrbit(
            Vec3 eye,
            Vec3 basePoint,
            Vec3 movingPoint,
            LivingEntity target,
            double time,
            double blend) {
        double strength =
                Mth.clamp(SilentAuraConfig.settledJitter(), 0.0D, 1.0D) * blend * noiseStrength;
        if (strength <= 0.0D) return movingPoint;
        double distance = Math.max(eye.distanceTo(basePoint), 0.3D);
        AABB box = target.getBoundingBox();
        double lateral = Math.min(distance * 0.014D * strength, box.getXsize() * 0.26D);
        if (lateral <= 1.0E-4D) return movingPoint;
        long seed = motionSeed ^ 0xBF58476D1CE4E5B9L;
        double viewX = basePoint.x - eye.x;
        double viewZ = basePoint.z - eye.z;
        double horizontalLength = Math.hypot(viewX, viewZ);
        double rightX = horizontalLength < 1.0E-6D ? 1.0D : -viewZ / horizontalLength;
        double rightZ = horizontalLength < 1.0E-6D ? 0.0D : viewX / horizontalLength;
        double forwardX = horizontalLength < 1.0E-6D ? 0.0D : viewX / horizontalLength;
        double forwardZ = horizontalLength < 1.0E-6D ? 1.0D : viewZ / horizontalLength;
        double sway = AimMotionNoise.sample(time * 1.25D, seed);
        double depth = AimMotionNoise.sample(time * 0.83D, seed ^ 0x9E3779B97F4A7C15L);
        Vec3 orbit =
                movingPoint
                        .add(rightX * sway * lateral, 0.0D, rightZ * sway * lateral)
                        .add(
                                forwardX * depth * lateral * 0.5D,
                                0.0D,
                                forwardZ * depth * lateral * 0.5D);
        double insetX = Math.min(box.getXsize() * 0.12D, 0.08D);
        double insetY = Math.min(box.getYsize() * 0.10D, 0.14D);
        double insetZ = Math.min(box.getZsize() * 0.12D, 0.08D);
        return MathUtils.closestPoint(
                orbit,
                new AABB(
                        box.minX + insetX,
                        box.minY + insetY,
                        box.minZ + insetZ,
                        box.maxX - insetX,
                        box.maxY - insetY,
                        box.maxZ - insetZ));
    }

    /**
     * Treats the target hitbox as a vertical pitch corridor. Airborne motion
     * must not pull pitch toward a moving aim point while the existing ray is
     * already safely inside the body. The hold remains soft, and fades out
     * continuously near an edge, so pitch never becomes an obvious flat line.
     */
    private Rotation applyAirbornePitchInertia(
            Minecraft client,
            LivingEntity target,
            Vec3 eye,
            Vec3 desiredPoint,
            Rotation desired,
            boolean lowerBodyFallback) {
        if (lowerBodyFallback || client.player.tickCount < crossingRecoveryUntilTick)
            return desired;
        Vec3 localVelocity = client.player.getDeltaMovement();
        Vec3 targetVelocity = prediction.velocity();
        boolean verticalMotion =
                (!client.player.onGround() && Math.abs(localVelocity.y) > 0.012D)
                        || (!target.onGround() && Math.abs(targetVelocity.y) > 0.012D);
        if (!verticalMotion) return desired;

        AABB box = target.getBoundingBox();
        double currentMargin = verticalRayMargin(client, eye, desired.yaw(), pitch, box);
        if (currentMargin <= 0.0D) return desired;

        double predictionStrength = Mth.clamp(SilentAuraConfig.prediction(), 0.0D, 3.0D);
        double horizonTicks =
                Mth.clamp(
                        SilentAuraConfig.predictionLead()
                                * predictionStrength
                                * aimType.pitchPredictionScale(),
                        0.0D,
                        1.25D);
        double futureMargin = currentMargin;
        float trackingPitch = desired.pitch();
        if (horizonTicks > 1.0E-4D) {
            Vec3 futureEye = TrajectoryPrediction.linearPosition(eye, localVelocity, horizonTicks);
            AABB futureBox = TrajectoryPrediction.linearBox(box, targetVelocity, horizonTicks);
            Vec3 futurePoint =
                    TrajectoryPrediction.linearPosition(desiredPoint, targetVelocity, horizonTicks);
            Rotation futureRotation = RotationUtils.rotationTo(futureEye, futurePoint);
            futureMargin =
                    verticalRayMargin(client, futureEye, futureRotation.yaw(), pitch, futureBox);

            double safety = aimType.pitchCorridorSafetyFraction();
            double futureUrgency =
                    1.0D
                            - smoothStep(
                                    Mth.clamp(
                                            futureMargin / Math.max(safety, 1.0E-4D), 0.0D, 1.0D));
            double predictionBlend =
                    futureUrgency * Mth.clamp(predictionStrength / 1.5D, 0.0D, 1.0D);
            trackingPitch =
                    (float) Mth.lerp(predictionBlend, trackingPitch, futureRotation.pitch());
        }

        double safeFraction = aimType.pitchCorridorSafetyFraction();
        double safeDepth =
                Mth.clamp(
                        Math.min(currentMargin, futureMargin) / Math.max(safeFraction, 1.0E-4D),
                        0.0D,
                        1.0D);
        double inertia = aimType.pitchInertiaStrength() * smoothStep(safeDepth);
        float softenedPitch = pitch + (trackingPitch - pitch) * (float) (1.0D - inertia);
        return new Rotation(desired.yaw(), softenedPitch);
    }

    /** Normalized vertical distance from the ray hit to the nearest box edge. */
    private static double verticalRayMargin(
            Minecraft client, Vec3 eye, float yaw, float pitch, AABB box) {
        if (box.contains(eye)) return 0.5D;
        Vec3 look = Vec3.directionFromRotation(pitch, yaw);
        double diagonal =
                Math.sqrt(
                        Mth.square(box.getXsize())
                                + Mth.square(box.getYsize())
                                + Mth.square(box.getZsize()));
        double length = eye.distanceTo(box.getCenter()) + diagonal + 1.0D;
        var hit = box.clip(eye, eye.add(look.scale(length)));
        if (hit.isEmpty() || !RaytraceUtils.canRayTraceTo(client, eye, hit.get())) {
            return 0.0D;
        }
        double height = Math.max(box.getYsize(), 0.1D);
        double fraction = Mth.clamp((hit.get().y - box.minY) / height, 0.0D, 1.0D);
        return Math.min(fraction, 1.0D - fraction);
    }

    private static double smoothStep(double value) {
        double clamped = Mth.clamp(value, 0.0D, 1.0D);
        return MathUtils.cubicSmoothStep(clamped);
    }

    /**
     * Crossing is horizontal, but the local body must still share the target's
     * vertical span. Using eye Y here excluded every ordinary jump-through:
     * the eye rises above a player box while the legs/torso still pass through
     * it, so the reverse-yaw preview never started.
     */
    private static boolean occupiesCrossingCorridor(
            Minecraft client, Vec3 eye, AABB box, double margin) {
        AABB corridor = box.inflate(margin);
        AABB localBox = client.player.getBoundingBox();
        boolean horizontalCentreInside =
                eye.x >= corridor.minX
                        && eye.x <= corridor.maxX
                        && eye.z >= corridor.minZ
                        && eye.z <= corridor.maxZ;
        boolean bodiesOverlapVertically =
                localBox.maxY >= corridor.minY && localBox.minY <= corridor.maxY;
        return horizontalCentreInside && bodiesOverlapVertically;
    }

    /**
     * Keeps pitch anchored at a stable world height while that height still
     * intersects the target. If a jump moves the complete hitbox past the
     * anchor, catch up gradually instead of copying the entity's Y displacement.
     */
    private Vec3 stabilizeAimHeight(
            LivingEntity target,
            Vec3 desired,
            double rawDelta,
            boolean targetChanged,
            double bodyFloorFraction) {
        AABB box = target.getBoundingBox();
        double minimum = Mth.lerp(bodyFloorFraction, box.minY, box.maxY);
        double maximum = Mth.lerp(UPPER_BODY_CEILING, box.minY, box.maxY);
        double preferred = Mth.clamp(desired.y, minimum, maximum);
        if (targetChanged || !Double.isFinite(stickyAimY)) {
            stickyAimY = preferred;
            lastTargetMinY = box.minY;
            return new Vec3(desired.x, stickyAimY, desired.z);
        }

        // Keep jump/fall acquisition in target prediction without raising the
        // packet-domain pitch acceleration bound.
        // Preserve the chosen fraction of the opponent's body when they jump
        // or fall. A world-space anchor otherwise sinks toward their legs.
        if (Double.isFinite(lastTargetMinY)) {
            stickyAimY += box.minY - lastTargetMinY;
        }
        lastTargetMinY = box.minY;

        double delta = Mth.clamp(rawDelta, 0.0D, 1.0D / 20.0D);
        boolean lowerBodyFallback = bodyFloorFraction < UPPER_BODY_FLOOR;
        if (lowerBodyFallback) {
            // Move promptly but continuously from the old chest anchor into
            // the exposed lower region. This is frame-rate independent and
            // avoids one-packet pitch snaps when cover enters the view.
            stickyAimY = approach(stickyAimY, preferred, 8.0D * delta);
            return new Vec3(desired.x, Mth.clamp(stickyAimY, minimum, maximum), desired.z);
        }

        boolean anchorWasInside = stickyAimY >= minimum && stickyAimY <= maximum;
        if (anchorWasInside) stickyAimY = Mth.clamp(stickyAimY, minimum, maximum);
        if (stickyAimY < minimum) {
            stickyAimY = Math.min(minimum, stickyAimY + 2.35D * delta);
        } else if (stickyAimY > maximum) {
            stickyAimY = Math.max(maximum, stickyAimY - 2.55D * delta);
        } else {
            // While the anchor remains inside the hitbox, only allow a very
            // slow correction toward the requested body point.
            stickyAimY = approach(stickyAimY, preferred, 0.12D * delta);
        }
        return new Vec3(desired.x, Mth.clamp(stickyAimY, minimum, maximum), desired.z);
    }

    /**
     * Carries the selector's visible body region through stabilization,
     * prediction and final clamping. Upper points keep the normal chest floor;
     * lower points retain their own height with only a tiny edge inset.
     */
    private static double bodyFloorFraction(Vec3 point, AABB box) {
        double height = Math.max(box.getYsize(), 0.1D);
        double fraction =
                Mth.clamp((point.y - box.minY) / height, LOWER_BODY_FLOOR, UPPER_BODY_CEILING);
        if (fraction >= UPPER_BODY_FLOOR) return UPPER_BODY_FLOOR;
        return Mth.clamp(fraction - 0.025D, LOWER_BODY_FLOOR, UPPER_BODY_FLOOR);
    }

    private static Vec3 deepestVisibleInset(
            Minecraft client, Vec3 eye, Vec3 surfacePoint, AABB box) {
        Vec3 center = box.getCenter();
        double[] insetFractions = {0.20D, 0.14D, 0.09D, 0.05D, 0.02D, 0.0D};
        for (double fraction : insetFractions) {
            Vec3 candidate = surfacePoint.lerp(center, fraction);
            if (RaytraceUtils.canRayTraceTo(client, eye, candidate)) {
                return candidate;
            }
        }
        return surfacePoint;
    }

    public boolean active() {
        return active;
    }

    public void beginFrom(float yaw, float pitch) {
        clear();
        this.yaw = yaw;
        this.pitch = pitch;
        active = true;
    }

    public boolean returning() {
        return returning;
    }

    public int targetId() {
        return targetId;
    }

    public float yaw() {
        return yaw;
    }

    public float pitch() {
        return pitch;
    }

    public boolean crossingTarget() {
        return crossingTarget;
    }

    public float bodyYaw() {
        return crossingTarget ? crossingBodyYaw : yaw;
    }

    public Vec3 lookVector() {
        return Vec3.directionFromRotation(pitch, yaw);
    }

    public void clear() {
        active = returning = false;
        targetId = -1;
        yawVelocity = pitchVelocity = 0.0F;
        pathJitterBlend = 0.0F;
        motionSeed = 0L;
        stickyAimY = Double.NaN;
        heldOrbitOffset = null;
        orbitOffset = Vec3.ZERO;
        prediction.reset();
        noiseTime = noiseStrength = 0.0D;
        noiseSpeed = 1.0D;
        nextAimSampleSeconds = 0.0D;
        flickYawAccelScale = flickPitchAccelScale = 1.0D;
        flickArmed = false;
        accelNoise = 1.0D;
        crossingTarget = false;
        crossingHeadYaw = crossingHeadPitch = crossingBodyYaw = 0.0F;
        crossingRecoveryUntilTick = Integer.MIN_VALUE;
        lastTargetMinY = Double.NaN;
        returnYaw = returnPitch = 0.0F;
        returnMotionSeed = 0L;
        nextReturnMotionSample = 0.0D;
        returnResponseScale = returnYawSpeedScale = returnPitchSpeedScale = 1.0D;
        aimType.reset();
    }

    private void clearAtCamera(Minecraft client) {
        // Re-base onto the camera yaw without a modulo snap: pick the 360°
        // equivalent closest to the current continuous yaw, otherwise crossing
        // the ±180 boundary emits a ~360° delta packet instead of a small turn.
        yaw += MathUtils.wrappedAngleDifference(yaw, client.player.getYRot());
        // The old implementation only rebased the controller and immediately
        // disabled it. The following vanilla packet therefore still used the
        // camera's wrapped numeric yaw (for example -179 after a sent 181),
        // producing a -360 degree delta. Preserve the visually identical
        // continuous equivalent on the actual player before releasing control.
        client.player.setYRot(yaw);
        pitch = client.player.getXRot();
        clear();
    }
}
