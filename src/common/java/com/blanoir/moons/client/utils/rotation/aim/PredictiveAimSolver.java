package com.blanoir.moons.client.utils.rotation.aim;

import static com.blanoir.moons.client.utils.math.MathUtils.approach;

import com.blanoir.moons.client.compat.math.Mth;
import com.blanoir.moons.client.compat.math.VecMath;
import com.blanoir.moons.client.utils.math.MathUtils;
import com.blanoir.moons.client.utils.math.RandomMath;
import com.blanoir.moons.client.utils.math.Smoothing;
import com.blanoir.moons.client.utils.prediction.AimPrediction;
import com.blanoir.moons.client.utils.prediction.MotionPrediction;
import com.blanoir.moons.client.utils.prediction.TrajectoryPrediction;
import com.blanoir.moons.client.utils.raytrace.RaytraceUtils;
import com.blanoir.moons.client.utils.rotation.Rotation;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.Vec3;

/**
 * B: Existing Lock/Balance predictive point processing and frame rotation. Camera return is G.
 * The mode supplies configuration, profile, frame delta and its own AimState.
 * observe/solve update only that state; no event registration, module config lookup,
 * camera writes, attack decisions or packet submission occur here. Preserve call order and
 * reset timing: branches consume random samples and carry prediction/crossing/orbit history.
 */
public final class PredictiveAimSolver {
    private PredictiveAimSolver() {}

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

    public static void observe(
            AimState state,
            AimProfile profile,
            AimParameters parameters,
            Minecraft client,
            EntityLivingBase target,
            double deltaSeconds) {
        var currentPlayer = client.thePlayer;
        MotionPrediction.Parameters predictionParameters = parameters.motionPredictionParameters();
        if (!state.prediction.parameters().equals(predictionParameters)) {
            state.prediction = new MotionPrediction(predictionParameters);
        }
        state.prediction.observe(
                currentPlayer.ticksExisted, VecMath.position(target), VecMath.motion(target));
        double frameDelta = Math.max(0.0D, Math.min(0.05D, deltaSeconds));
        double parameterBlend = Smoothing.exponentialResponse(12.0D, frameDelta);
        state.noiseStrength += (parameters.jitter() - state.noiseStrength) * parameterBlend;
        state.noiseSpeed += (parameters.jitterSpeed() - state.noiseSpeed) * parameterBlend;
        state.noiseTime += frameDelta * state.noiseSpeed;
    }

    public static void solve(
            AimState state,
            AimProfile profile,
            AimParameters parameters,
            Minecraft client,
            EntityLivingBase target,
            Vec3 point,
            double deltaSeconds,
            boolean targetChanged) {
        var currentPlayer = client.thePlayer;
        double frameDelta = Math.max(0.0D, Math.min(0.05D, deltaSeconds));
        double time = state.noiseTime;
        Vec3 eye = currentPlayer.getPositionEyes(1F);
        AxisAlignedBB targetBox = target.getEntityBoundingBox();
        Vec3 relativeVelocity = state.prediction.velocity().subtract(VecMath.motion(currentPlayer));
        double targetYawRate =
                AimPrediction.yawRateDegrees(eye, VecMath.center(targetBox), relativeVelocity);
        if (!state.crossingTarget && Math.abs(targetYawRate) > 0.01D) {
            state.crossingTurnDirection = (float) Math.signum(targetYawRate);
        }
        double bodyFloorFraction = bodyFloorFraction(state, profile, parameters, point, targetBox);
        boolean lowerBodyFallback = bodyFloorFraction < UPPER_BODY_FLOOR;
        boolean enteringCrossing =
                !state.crossingTarget
                        && occupiesCrossingCorridor(
                                state,
                                profile,
                                parameters,
                                client,
                                eye,
                                targetBox,
                                CROSSING_ENTER_MARGIN);
        if (enteringCrossing) {
            state.crossingTarget = true;
            state.crossingHeadYaw = state.yaw;
            state.crossingHeadPitch = state.pitch;
            state.crossingBodyYaw = state.yaw;
            if (state.crossingTurnDirection == 0.0F) {
                state.crossingTurnDirection =
                        state.yawVelocity == 0.0F ? 1.0F : Math.signum(state.yawVelocity);
            }
            state.yawVelocity = state.pitchVelocity = 0.0F;
            state.heldOrbitOffset = null;
        } else if (state.crossingTarget
                && !occupiesCrossingCorridor(
                        state, profile, parameters, client, eye, targetBox, CROSSING_EXIT_MARGIN)) {
            state.crossingTarget = false;
            // The airborne corridor was introduced to avoid needless pitch
            // corrections while a valid ray remains inside the body. Once we
            // have crossed out of that body it must release immediately;
            // otherwise a long landing keeps the pre-crossing pitch and the
            // attack gate appears to stall for several ticks.
            state.crossingRecoveryUntilTick = currentPlayer.ticksExisted + 2;
            state.pathJitterBlend = 0.0F;
            state.heldOrbitOffset = null;
        }

        if (state.crossingTarget) {
            // A point inside an AABB has no stable look direction. As the eye
            // crosses its centre, atan2 changes side and the computed pitch can
            // dive toward the feet for one frame. Track only the stable
            // horizontal direction while inside the box. The old code froze
            // yaw and pitch at their entry values; repeated look packets with
            // that exact pair are what ACA EqualRotation checks directly.
            Vec3 centre = VecMath.center(targetBox);
            double lookahead =
                    AimPrediction.crossingLookaheadTicks(
                            VecMath.motion(currentPlayer),
                            state.prediction.velocity(),
                            eye,
                            targetBox,
                            CROSSING_EXIT_MARGIN);
            Vec3 localVelocity = VecMath.motion(currentPlayer);
            Vec3 targetVelocity = state.prediction.velocity();
            Vec3 yawEye =
                    lookahead <= 0.0D
                            ? eye
                            : eye.addVector(
                                    localVelocity.xCoord * lookahead,
                                    0.0D,
                                    localVelocity.zCoord * lookahead);
            Vec3 yawCentre =
                    lookahead <= 0.0D
                            ? centre
                            : centre.addVector(
                                    targetVelocity.xCoord * lookahead,
                                    0.0D,
                                    targetVelocity.zCoord * lookahead);
            double horizontalSquared =
                    Mth.square(yawCentre.xCoord - yawEye.xCoord)
                            + Mth.square(yawCentre.zCoord - yawEye.zCoord);
            if (horizontalSquared > 1.0E-4D) {
                state.crossingBodyYaw =
                        AimAngles.rotationTo(
                                        yawEye,
                                        new Vec3(yawCentre.xCoord, yawEye.yCoord, yawCentre.zCoord))
                                .yaw();
            }
            double crossingDelta = Mth.clamp(deltaSeconds, 0.0D, 1.0D / 20.0D);
            // Keep the 20 TPS target step below the consecutive >20
            // degree snap threshold. The packet smoother applies an 18-degree
            // backstop in case render sampling carries momentum into the box.
            boolean matrixProfile = parameters.matrixCompatibility();
            double crossingYawRate =
                    matrixProfile
                            ? (profile.lock() ? 320.0D : 220.0D)
                            : (profile.lock() ? 900.0D : 620.0D);
            float yawLimit = (float) (crossingYawRate * crossingDelta);
            float previousCrossingYaw = state.crossingHeadYaw;
            float previousCrossingPitch = state.crossingHeadPitch;
            float crossingTurn =
                    MathUtils.wrappedAngleDifference(state.crossingHeadYaw, state.crossingBodyYaw);
            // Keep the approach side through the ambiguous half-turn seam.
            // Tiny position changes must not alternate a 180-degree turn's direction.
            if (Math.abs(crossingTurn) >= 175.0F
                    && crossingTurn * state.crossingTurnDirection < 0.0F) {
                crossingTurn += Math.copySign(360.0F, state.crossingTurnDirection);
            }
            state.crossingHeadYaw += Mth.clamp(crossingTurn, -yawLimit, yawLimit);
            boolean eyeInsideTarget =
                    VecMath.contains(VecMath.inflate(targetBox, CROSSING_ENTER_MARGIN), eye);
            // Inside the body, preserve valid pitch. Above it, use actual exit
            // geometry; a fixed 2-5 degree yaw-coupled pitch had no geometric basis.
            float crossingPitchTarget =
                    eyeInsideTarget
                            ? state.crossingHeadPitch
                            : AimAngles.rotationTo(
                                            yawEye,
                                            new Vec3(
                                                    yawCentre.xCoord,
                                                    point.yCoord,
                                                    yawCentre.zCoord))
                                    .pitch();
            double crossingPitchRate =
                    matrixProfile
                            ? (profile.lock() ? 18.0D : 14.0D)
                            : (profile.lock() ? 360.0D : 260.0D);
            float pitchLimit = (float) (crossingPitchRate * crossingDelta);
            state.crossingHeadPitch =
                    approach(state.crossingHeadPitch, crossingPitchTarget, pitchLimit);
            state.yaw = state.crossingHeadYaw;
            state.pitch = state.crossingHeadPitch;
            // Preserve finite velocities for the exit frame instead of
            // restarting a turn from zero at the wider crossing boundary.
            state.yawVelocity =
                    MathUtils.wrappedAngleDifference(previousCrossingYaw, state.yaw)
                            / (float) crossingDelta;
            state.pitchVelocity = (state.pitch - previousCrossingPitch) / (float) crossingDelta;
            state.pathJitterBlend =
                    approach(
                            state.pathJitterBlend,
                            0.0F,
                            (float) (Mth.clamp(deltaSeconds, 0.0D, 0.05D) * 12.0D));
            return;
        }

        Rotation baseRotation = AimAngles.rotationTo(eye, point);
        double remaining = MathUtils.angularDistance(state.yaw, state.pitch, baseRotation);
        // A new flick gets its own muscle strength; reusing one scale across
        // turns makes every turn's acceleration ramp identical.
        if (remaining > 8.0D && !state.flickArmed) {
            state.flickArmed = true;
            state.flickYawAccelScale = profile.sampleFlickYawScale();
            state.flickPitchAccelScale = profile.sampleFlickPitchScale();
        } else if (remaining < 2.0D) {
            state.flickArmed = false;
        }
        state.accelNoise =
                1.0D
                        + SeededMotionNoise.sample(time * 3.7D, state.motionSeed)
                                * (profile.lock() ? 0.04D : 0.10D)
                                * state.noiseStrength;
        double angularSpeed = Math.hypot(state.yawVelocity, state.pitchVelocity);
        float pathDemand =
                (float)
                        Math.max(
                                Mth.clamp((remaining - 0.35D) / 6.0D, 0.0D, 1.0D),
                                Mth.clamp(angularSpeed / 260.0D, 0.0D, 0.75D));
        double blendRate = pathDemand > state.pathJitterBlend ? 5.5D : 9.0D;
        state.pathJitterBlend =
                approach(
                        state.pathJitterBlend,
                        pathDemand,
                        (float) (Mth.clamp(deltaSeconds, 0.0D, 0.05D) * blendRate));
        // Preserve a small settled orbit instead of freezing on one point.
        // Full jitter is reserved for the active turn path.
        double effectiveJitter =
                Mth.clamp(
                        state.noiseStrength * (0.28D + state.pathJitterBlend * 0.72D), 0.0D, 1.0D);
        effectiveJitter *= profile.jitterScale();
        // A lower-body point means the selector could not see the preferred
        // upper region. Keep that exact exposed opening instead of wandering
        // the point back behind the block.
        if (lowerBodyFallback) effectiveJitter = 0.0D;
        Vec3 jitteredPoint =
                HitboxJitter.insideHitbox(
                        eye,
                        point,
                        target.getEntityBoundingBox(),
                        time,
                        state.motionSeed,
                        effectiveJitter,
                        1.0D);
        if (!profile.lock()) {
            // Balance humanizes yaw/depth only. Pitch must represent actual
            // geometry, not a noise channel, otherwise a level target causes
            // needless head lifts even though the horizontal ray is valid.
            jitteredPoint = new Vec3(jitteredPoint.xCoord, point.yCoord, jitteredPoint.zCoord);
        }
        Vec3 anchoredPoint =
                stabilizeAimHeight(
                        state,
                        profile,
                        parameters,
                        target,
                        jitteredPoint,
                        deltaSeconds,
                        targetChanged,
                        bodyFloorFraction);
        Vec3 movingPoint =
                leadAimPoint(
                        state,
                        profile,
                        parameters,
                        client,
                        target,
                        anchoredPoint,
                        lowerBodyFallback);
        double predictionStrength = Mth.clamp(parameters.prediction(), 0.0D, 3.0D);
        double assist = Mth.clamp(Math.abs(targetYawRate) / 8.0D * predictionStrength, 0.0D, 1.0D);
        // Motion feed-forward follows the changing bearing even when the
        // positional lead has reached the edge of the current hitbox.
        state.yawFeedForward =
                lowerBodyFallback
                        ? 0.0D
                        : Mth.clamp(
                                targetYawRate * 20.0D * Math.min(predictionStrength, 1.0D),
                                -profile.maxYawSpeed(),
                                profile.maxYawSpeed());
        double jitter = effectiveJitter;
        long seed = state.motionSeed;
        double responseVariation =
                1.0D + SeededMotionNoise.sample(time * 0.91D, seed) * 0.12D * jitter;
        double yawAccelerationVariation =
                1.0D
                        + SeededMotionNoise.sample(time * 2.15D, seed ^ 0x9E3779B97F4A7C15L)
                                * 0.28D
                                * jitter;
        double pitchAccelerationVariation =
                1.0D
                        + SeededMotionNoise.sample(time * 1.87D, seed ^ 0x94D049BB133111EBL)
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
                profile.response(parameters.smooth()) * responseVariation * visibilityUrgency;
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
                profile.lock() || lowerBodyFallback
                        ? movingPoint
                        : settledOrbit(
                                state,
                                profile,
                                parameters,
                                eye,
                                point,
                                movingPoint,
                                target,
                                time,
                                settle * (0.25D + 0.75D * state.pathJitterBlend));
        Vec3 offsetCandidate = orbitCandidate.subtract(movingPoint);
        if (lowerBodyFallback) {
            state.heldOrbitOffset = VecMath.ZERO;
            state.nextAimSampleSeconds = time;
        } else if (targetChanged
                || state.heldOrbitOffset == null
                || time >= state.nextAimSampleSeconds) {
            if (targetChanged
                    || state.heldOrbitOffset == null
                    || VecMath.lengthSqr(offsetCandidate.subtract(state.heldOrbitOffset))
                            > ORBIT_OFFSET_DEADZONE * ORBIT_OFFSET_DEADZONE) {
                state.heldOrbitOffset = offsetCandidate;
            }
            state.nextAimSampleSeconds =
                    time
                            + RandomMath.between(
                                    profile.aimSampleMinSeconds(), profile.aimSampleMaxSeconds());
        }
        state.orbitOffset =
                lowerBodyFallback
                        ? VecMath.ZERO
                        : VecMath.lerp(
                                state.orbitOffset,
                                state.heldOrbitOffset,
                                Smoothing.exponentialResponse(16.0D, frameDelta));
        Vec3 desiredPoint = movingPoint.add(state.orbitOffset);
        AxisAlignedBB aimBox = target.getEntityBoundingBox();
        double insetX =
                lowerBodyFallback ? 0.002D : Math.min((aimBox.maxX - aimBox.minX) * 0.12D, 0.08D);
        double insetZ =
                lowerBodyFallback ? 0.002D : Math.min((aimBox.maxZ - aimBox.minZ) * 0.12D, 0.08D);
        double bodyFloor = Mth.lerp(bodyFloorFraction, aimBox.minY, aimBox.maxY);
        double upperBodyCeiling = Mth.lerp(UPPER_BODY_CEILING, aimBox.minY, aimBox.maxY);
        boolean localAirborne =
                !currentPlayer.onGround && Math.abs(VecMath.motion(currentPlayer).yCoord) > 0.012D;
        double desiredY = desiredPoint.yCoord;
        if (localAirborne && !lowerBodyFallback) {
            // Stay inside the same valid hitbox while choosing a height closer
            // to our moving eye. This reduces needless head nodding during our
            // own jump without weakening the pitch needed to keep the ray valid.
            double calmY = Mth.clamp(eye.yCoord, bodyFloor, upperBodyCeiling);
            desiredY = Mth.lerp(AIRBORNE_AIM_HEIGHT_BLEND, desiredY, calmY);
        }
        desiredPoint =
                new Vec3(
                        Mth.clamp(desiredPoint.xCoord, aimBox.minX + insetX, aimBox.maxX - insetX),
                        Mth.clamp(desiredY, bodyFloor, upperBodyCeiling),
                        Mth.clamp(desiredPoint.zCoord, aimBox.minZ + insetZ, aimBox.maxZ - insetZ));
        if (!RaytraceUtils.canRayTraceTo(client, eye, desiredPoint, parameters.throughBlocks())) {
            // Prediction/jitter can move an originally visible top-edge point
            // behind the ledge when attacking downward. Recover the selector's
            // ray and inset it only as far as visibility permits, giving the
            // quantized attack ray real hitbox margin without changing motion.
            desiredPoint =
                    deepestVisibleInset(state, profile, parameters, client, eye, point, aimBox);
        }
        double arriveEase = profile.arriveEase(settle, parameters.settledJitter());
        Rotation desiredRotation = AimAngles.rotationTo(eye, desiredPoint);
        AimProfile.Correction correction =
                profile.correct(state.correction, desiredRotation, state.yaw, time, targetChanged);
        desiredRotation = correction.rotation();
        desiredRotation =
                applyAirbornePitchInertia(
                        state,
                        profile,
                        parameters,
                        client,
                        target,
                        eye,
                        desiredPoint,
                        desiredRotation,
                        lowerBodyFallback);
        AimPursuitStep.stepToward(
                state,
                profile,
                desiredRotation,
                deltaSeconds,
                response * arriveEase * correction.responseScale(),
                profile.maxYawSpeed(),
                profile.maxPitchSpeed(),
                state.accelNoise
                        * state.flickYawAccelScale
                        * visibilityUrgency
                        * (1.0D + assist * profile.yawAssistScale())
                        * yawAccelerationVariation
                        * correction.accelerationScale(),
                state.accelNoise
                        * state.flickPitchAccelScale
                        * visibilityUrgency
                        * (1.0D + assist * profile.pitchAssistScale())
                        * pitchAccelerationVariation
                        * correction.accelerationScale());
    }

    public static Vec3 leadAimPoint(
            AimState state,
            AimProfile profile,
            AimParameters parameters,
            Minecraft client,
            EntityLivingBase target,
            Vec3 point,
            boolean lowerBodyFallback) {
        double baseLead =
                parameters.predictionLead()
                        * parameters.prediction()
                        * (profile.lock() ? 1.15D : 0.85D);
        // Prediction is useful on an open hitbox, but when only a leg-sized
        // opening is exposed it can move an otherwise visible point behind the
        // cover. The live selector already follows the moving entity.
        if (baseLead <= 0.0D || lowerBodyFallback) return point;
        Rotation currentBearing = AimAngles.rotationTo(client.thePlayer.getPositionEyes(1F), point);
        double leadTicks =
                AimPrediction.turnLookaheadTicks(
                        baseLead,
                        MathUtils.wrappedAngleDifference(state.yaw, currentBearing.yaw()),
                        profile.maxYawSpeed() / 20.0D,
                        profile.response(parameters.smooth()) / 20.0D,
                        state.prediction.parameters().maxHorizonTicks());
        Vec3 localVelocity = VecMath.motion(client.thePlayer);
        Vec3 relativeTravel =
                state.prediction
                        .displacement(leadTicks)
                        .subtract(
                                localVelocity.xCoord * leadTicks,
                                0.0D,
                                localVelocity.zCoord * leadTicks);
        AxisAlignedBB box = target.getEntityBoundingBox();
        double upperBodyFloor = Mth.lerp(UPPER_BODY_FLOOR, box.minY, box.maxY);
        double upperBodyCeiling = Mth.lerp(UPPER_BODY_CEILING, box.minY, box.maxY);
        return AimPrediction.clampedLead(
                point,
                relativeTravel,
                new AxisAlignedBB(
                        box.minX, upperBodyFloor, box.minZ, box.maxX, upperBodyCeiling, box.maxZ));
    }

    public static Vec3 settledOrbit(
            AimState state,
            AimProfile profile,
            AimParameters parameters,
            Vec3 eye,
            Vec3 basePoint,
            Vec3 movingPoint,
            EntityLivingBase target,
            double time,
            double blend) {
        double strength =
                Mth.clamp(parameters.settledJitter(), 0.0D, 1.0D) * blend * state.noiseStrength;
        if (strength <= 0.0D) return movingPoint;
        double distance = Math.max(eye.distanceTo(basePoint), 0.3D);
        AxisAlignedBB box = target.getEntityBoundingBox();
        double lateral = Math.min(distance * 0.014D * strength, (box.maxX - box.minX) * 0.26D);
        if (lateral <= 1.0E-4D) return movingPoint;
        long seed = state.motionSeed ^ 0xBF58476D1CE4E5B9L;
        double viewX = basePoint.xCoord - eye.xCoord;
        double viewZ = basePoint.zCoord - eye.zCoord;
        double horizontalLength = Math.hypot(viewX, viewZ);
        double rightX = horizontalLength < 1.0E-6D ? 1.0D : -viewZ / horizontalLength;
        double rightZ = horizontalLength < 1.0E-6D ? 0.0D : viewX / horizontalLength;
        double forwardX = horizontalLength < 1.0E-6D ? 0.0D : viewX / horizontalLength;
        double forwardZ = horizontalLength < 1.0E-6D ? 1.0D : viewZ / horizontalLength;
        double sway = SeededMotionNoise.sample(time * 1.25D, seed);
        double depth = SeededMotionNoise.sample(time * 0.83D, seed ^ 0x9E3779B97F4A7C15L);
        Vec3 orbit =
                movingPoint
                        .addVector(rightX * sway * lateral, 0.0D, rightZ * sway * lateral)
                        .addVector(
                                forwardX * depth * lateral * 0.5D,
                                0.0D,
                                forwardZ * depth * lateral * 0.5D);
        double insetX = Math.min((box.maxX - box.minX) * 0.12D, 0.08D);
        double insetY = Math.min((box.maxY - box.minY) * 0.10D, 0.14D);
        double insetZ = Math.min((box.maxZ - box.minZ) * 0.12D, 0.08D);
        return MathUtils.closestPoint(
                orbit,
                new AxisAlignedBB(
                        box.minX + insetX,
                        box.minY + insetY,
                        box.minZ + insetZ,
                        box.maxX - insetX,
                        box.maxY - insetY,
                        box.maxZ - insetZ));
    }

    public static Rotation applyAirbornePitchInertia(
            AimState state,
            AimProfile profile,
            AimParameters parameters,
            Minecraft client,
            EntityLivingBase target,
            Vec3 eye,
            Vec3 desiredPoint,
            Rotation desired,
            boolean lowerBodyFallback) {
        if (lowerBodyFallback || client.thePlayer.ticksExisted < state.crossingRecoveryUntilTick)
            return desired;
        Vec3 localVelocity = VecMath.motion(client.thePlayer);
        Vec3 targetVelocity = state.prediction.velocity();
        boolean verticalMotion =
                (!client.thePlayer.onGround && Math.abs(localVelocity.yCoord) > 0.012D)
                        || (!target.onGround && Math.abs(targetVelocity.yCoord) > 0.012D);
        if (!verticalMotion) return desired;

        AxisAlignedBB box = target.getEntityBoundingBox();
        double currentMargin =
                verticalRayMargin(
                        state, profile, parameters, client, eye, desired.yaw(), state.pitch, box);
        if (currentMargin <= 0.0D) return desired;

        double predictionStrength = Mth.clamp(parameters.prediction(), 0.0D, 3.0D);
        double horizonTicks =
                Mth.clamp(
                        parameters.predictionLead()
                                * predictionStrength
                                * profile.pitchPredictionScale(),
                        0.0D,
                        1.25D);
        double futureMargin = currentMargin;
        float trackingPitch = desired.pitch();
        if (horizonTicks > 1.0E-4D) {
            Vec3 futureEye = TrajectoryPrediction.linearPosition(eye, localVelocity, horizonTicks);
            AxisAlignedBB futureBox =
                    TrajectoryPrediction.linearBox(box, targetVelocity, horizonTicks);
            Vec3 futurePoint =
                    TrajectoryPrediction.linearPosition(desiredPoint, targetVelocity, horizonTicks);
            Rotation futureRotation = AimAngles.rotationTo(futureEye, futurePoint);
            futureMargin =
                    verticalRayMargin(
                            state,
                            profile,
                            parameters,
                            client,
                            futureEye,
                            futureRotation.yaw(),
                            state.pitch,
                            futureBox);

            double safety = profile.pitchCorridorSafetyFraction();
            double futureUrgency =
                    1.0D
                            - smoothStep(
                                    state,
                                    profile,
                                    parameters,
                                    Mth.clamp(
                                            futureMargin / Math.max(safety, 1.0E-4D), 0.0D, 1.0D));
            double predictionBlend =
                    futureUrgency * Mth.clamp(predictionStrength / 1.5D, 0.0D, 1.0D);
            trackingPitch =
                    (float) Mth.lerp(predictionBlend, trackingPitch, futureRotation.pitch());
        }

        double safeFraction = profile.pitchCorridorSafetyFraction();
        double safeDepth =
                Mth.clamp(
                        Math.min(currentMargin, futureMargin) / Math.max(safeFraction, 1.0E-4D),
                        0.0D,
                        1.0D);
        double inertia =
                profile.pitchInertiaStrength() * smoothStep(state, profile, parameters, safeDepth);
        float softenedPitch =
                state.pitch + (trackingPitch - state.pitch) * (float) (1.0D - inertia);
        return new Rotation(desired.yaw(), softenedPitch);
    }

    public static double verticalRayMargin(
            AimState state,
            AimProfile profile,
            AimParameters parameters,
            Minecraft client,
            Vec3 eye,
            float yaw,
            float pitch,
            AxisAlignedBB box) {
        if (VecMath.contains(box, eye)) return 0.5D;
        Vec3 look = VecMath.directionFromRotation(pitch, yaw);
        double diagonal =
                Math.sqrt(
                        Mth.square((box.maxX - box.minX))
                                + Mth.square((box.maxY - box.minY))
                                + Mth.square((box.maxZ - box.minZ)));
        double length = eye.distanceTo(VecMath.center(box)) + diagonal + 1.0D;
        var hit = VecMath.clip(box, eye, eye.add(VecMath.scale(look, length)));
        if (hit.isEmpty()
                || !RaytraceUtils.canRayTraceTo(
                        client, eye, hit.get(), parameters.throughBlocks())) {
            return 0.0D;
        }
        double height = Math.max((box.maxY - box.minY), 0.1D);
        double fraction = Mth.clamp((hit.get().yCoord - box.minY) / height, 0.0D, 1.0D);
        return Math.min(fraction, 1.0D - fraction);
    }

    public static double smoothStep(
            AimState state, AimProfile profile, AimParameters parameters, double value) {
        double clamped = Mth.clamp(value, 0.0D, 1.0D);
        return MathUtils.cubicSmoothStep(clamped);
    }

    public static boolean occupiesCrossingCorridor(
            AimState state,
            AimProfile profile,
            AimParameters parameters,
            Minecraft client,
            Vec3 eye,
            AxisAlignedBB box,
            double margin) {
        AxisAlignedBB corridor = VecMath.inflate(box, margin);
        AxisAlignedBB localBox = client.thePlayer.getEntityBoundingBox();
        boolean horizontalCentreInside =
                eye.xCoord >= corridor.minX
                        && eye.xCoord <= corridor.maxX
                        && eye.zCoord >= corridor.minZ
                        && eye.zCoord <= corridor.maxZ;
        boolean bodiesOverlapVertically =
                localBox.maxY >= corridor.minY && localBox.minY <= corridor.maxY;
        return horizontalCentreInside && bodiesOverlapVertically;
    }

    public static Vec3 stabilizeAimHeight(
            AimState state,
            AimProfile profile,
            AimParameters parameters,
            EntityLivingBase target,
            Vec3 desired,
            double rawDelta,
            boolean targetChanged,
            double bodyFloorFraction) {
        AxisAlignedBB box = target.getEntityBoundingBox();
        double minimum = Mth.lerp(bodyFloorFraction, box.minY, box.maxY);
        double maximum = Mth.lerp(UPPER_BODY_CEILING, box.minY, box.maxY);
        double preferred = Mth.clamp(desired.yCoord, minimum, maximum);
        if (targetChanged || !Double.isFinite(state.stickyAimY)) {
            state.stickyAimY = preferred;
            state.lastTargetMinY = box.minY;
            return new Vec3(desired.xCoord, state.stickyAimY, desired.zCoord);
        }

        // Keep jump/fall acquisition in target prediction without raising the
        // packet-domain pitch acceleration bound.
        // Preserve the chosen fraction of the opponent's body when they jump
        // or fall. A world-space anchor otherwise sinks toward their legs.
        if (Double.isFinite(state.lastTargetMinY)) {
            state.stickyAimY += box.minY - state.lastTargetMinY;
        }
        state.lastTargetMinY = box.minY;

        double delta = Mth.clamp(rawDelta, 0.0D, 1.0D / 20.0D);
        boolean lowerBodyFallback = bodyFloorFraction < UPPER_BODY_FLOOR;
        if (lowerBodyFallback) {
            // Move promptly but continuously from the old chest anchor into
            // the exposed lower region. This is frame-rate independent and
            // avoids one-packet pitch snaps when cover enters the view.
            state.stickyAimY = approach(state.stickyAimY, preferred, 8.0D * delta);
            return new Vec3(
                    desired.xCoord, Mth.clamp(state.stickyAimY, minimum, maximum), desired.zCoord);
        }

        boolean anchorWasInside = state.stickyAimY >= minimum && state.stickyAimY <= maximum;
        if (anchorWasInside) state.stickyAimY = Mth.clamp(state.stickyAimY, minimum, maximum);
        if (state.stickyAimY < minimum) {
            state.stickyAimY = Math.min(minimum, state.stickyAimY + 2.35D * delta);
        } else if (state.stickyAimY > maximum) {
            state.stickyAimY = Math.max(maximum, state.stickyAimY - 2.55D * delta);
        } else {
            // While the anchor remains inside the hitbox, only allow a very
            // slow correction toward the requested body point.
            state.stickyAimY = approach(state.stickyAimY, preferred, 0.12D * delta);
        }
        return new Vec3(
                desired.xCoord, Mth.clamp(state.stickyAimY, minimum, maximum), desired.zCoord);
    }

    public static double bodyFloorFraction(
            AimState state,
            AimProfile profile,
            AimParameters parameters,
            Vec3 point,
            AxisAlignedBB box) {
        double height = Math.max((box.maxY - box.minY), 0.1D);
        double fraction =
                Mth.clamp((point.yCoord - box.minY) / height, LOWER_BODY_FLOOR, UPPER_BODY_CEILING);
        if (fraction >= UPPER_BODY_FLOOR) return UPPER_BODY_FLOOR;
        return Mth.clamp(fraction - 0.025D, LOWER_BODY_FLOOR, UPPER_BODY_FLOOR);
    }

    public static Vec3 deepestVisibleInset(
            AimState state,
            AimProfile profile,
            AimParameters parameters,
            Minecraft client,
            Vec3 eye,
            Vec3 surfacePoint,
            AxisAlignedBB box) {
        Vec3 center = VecMath.center(box);
        double[] insetFractions = {0.20D, 0.14D, 0.09D, 0.05D, 0.02D, 0.0D};
        for (double fraction : insetFractions) {
            Vec3 candidate = VecMath.lerp(surfacePoint, center, fraction);
            if (RaytraceUtils.canRayTraceTo(client, eye, candidate, parameters.throughBlocks())) {
                return candidate;
            }
        }
        return surfacePoint;
    }
}
