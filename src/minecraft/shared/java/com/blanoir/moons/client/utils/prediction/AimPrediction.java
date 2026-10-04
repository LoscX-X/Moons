package com.blanoir.moons.client.utils.prediction;

import com.blanoir.moons.client.compat.math.Mth;
import com.blanoir.moons.client.compat.math.VecMath;
import com.blanoir.moons.client.utils.math.MathUtils;
import com.blanoir.moons.client.utils.raytrace.RaytraceUtils;
import com.blanoir.moons.client.utils.rotation.Rotation;
import com.blanoir.moons.client.utils.rotation.aim.AimGeometry;
import com.blanoir.moons.client.utils.rotation.aim.AimPointsA;
import com.blanoir.moons.client.utils.rotation.aim.AimPointsB;
import com.blanoir.moons.client.utils.rotation.aim.AimPointsF;
import com.blanoir.moons.client.utils.rotation.aim.AimSolverD;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.Vec3;

/** Aim alignment, bounded lead and crossing forecasts with caller-supplied settings. */
public final class AimPrediction {
    private static final double CROSSING_EXIT_LOOKAHEAD_TICKS = 5.0D;
    private static final double CROSSING_MIN_RELATIVE_SPEED_SQUARED = 0.0016D;

    private AimPrediction() {}

    public static Vec3 clampedLead(Vec3 point, Vec3 travel, AxisAlignedBB bounds) {
        return MathUtils.closestPoint(point.add(travel), bounds);
    }

    /** Include pursuit response and turn time in a bounded, tick-domain lead. */
    public static double turnLookaheadTicks(
            double baseLead,
            double yawError,
            double maxYawPerTick,
            double responsePerTick,
            double maximumTicks) {
        if (baseLead <= 0.0D || maximumTicks <= 0.0D) return 0.0D;
        double turnTicks = Math.abs(yawError) / Math.max(maxYawPerTick, 1.0E-4D);
        double responseTicks = 1.0D / Math.max(responsePerTick, 1.0E-4D);
        return Mth.clamp(baseLead * (1.0D + responseTicks + turnTicks), 0.0D, maximumTicks);
    }

    /** Signed bearing velocity in degrees/tick; relative velocity is target minus observer. */
    public static double yawRateDegrees(Vec3 eye, Vec3 point, Vec3 relativeVelocity) {
        double x = point.xCoord - eye.xCoord;
        double z = point.zCoord - eye.zCoord;
        double distanceSquared = x * x + z * z;
        if (distanceSquared < 1.0E-6D) return 0.0D;
        return Math.toDegrees(
                (x * relativeVelocity.zCoord - z * relativeVelocity.xCoord) / distanceSquared);
    }

    public static AimForecast forecastAttack(
            Minecraft client,
            EntityLivingBase target,
            int ticksAhead,
            Vec3 futureEye,
            boolean enabled,
            double smooth,
            double range,
            double inputMultiplier,
            double minimumAngle,
            AimGeometry.Mode pointMode) {
        var currentPlayer = client.thePlayer;
        int ticks = Math.max(0, ticksAhead);

        Vec3 targetMotion = VecMath.motion(target);

        AxisAlignedBB futureBox =
                TrajectoryPrediction.linearBox(target.getEntityBoundingBox(), targetMotion, ticks);

        Vec3 futureAimPoint =
                pointMode == null
                        ? AimPointsF.closest(futureBox, futureEye, currentPlayer.getLook(1F), range)
                        : pointMode == AimGeometry.Mode.CENTER
                                ? AimPointsA.centerTrackingPoint(futureEye, futureBox)
                                : AimPointsB.closestTrackingPoint(futureEye, futureBox);

        boolean visible = RaytraceUtils.canRayTraceTo(client, futureEye, futureAimPoint);

        if (!visible) {
            Vec3 center = VecMath.center(futureBox);

            if (RaytraceUtils.canRayTraceTo(client, futureEye, center)) {
                futureAimPoint = center;
                visible = true;
            }
        }

        Rotation rotation = AimSolverD.rotationTo(futureEye, futureAimPoint);

        double yawError = Math.abs(Mth.wrapDegrees(rotation.yaw() - currentPlayer.rotationYaw));

        double pitchError = Math.abs(rotation.pitch() - currentPlayer.rotationPitch);

        double angularError = Math.hypot(yawError, pitchError);

        boolean currentlyOnTarget =
                client.objectMouseOver != null && client.objectMouseOver.entityHit == target;

        int ticksUntilAligned;

        if (enabled) {
            double effectiveSmooth = Mth.clamp(smooth * inputMultiplier, 0.01D, 0.99D);

            ticksUntilAligned = estimateAlignmentTicks(angularError, effectiveSmooth, minimumAngle);
        } else {
            ticksUntilAligned = currentlyOnTarget && ticks == 0 ? 0 : Integer.MAX_VALUE;
        }

        double targetSpeed = targetMotion.lengthVector();

        double motionUncertainty = Mth.clamp(targetSpeed * ticks * 0.18D, 0.0D, 0.75D);

        double angleConfidence = Math.exp(-angularError / 28.0D);

        double alignmentConfidence;

        if (ticksUntilAligned == Integer.MAX_VALUE) {
            alignmentConfidence =
                    currentlyOnTarget ? Math.exp(-targetSpeed * ticks * 0.45D) : 0.15D;
        } else {
            alignmentConfidence =
                    ticksUntilAligned <= ticks
                            ? 1.0D
                            : Math.exp(-(ticksUntilAligned - ticks) * 0.8D);
        }

        double confidence =
                visible
                        ? Mth.clamp(
                                angleConfidence * alignmentConfidence * (1.0D - motionUncertainty),
                                0.0D,
                                1.0D)
                        : 0.0D;

        if (currentlyOnTarget && ticks == 0) {
            confidence = 1.0D;
        }

        return new AimForecast(
                visible,
                angularError,
                ticksUntilAligned,
                confidence,
                futureAimPoint,
                inputMultiplier);
    }

    public static int estimateAlignmentTicks(
            double angularError, double effectiveSmooth, double minimumAngle) {
        if (angularError <= minimumAngle) {
            return 0;
        }

        double retention = 1.0D - effectiveSmooth;

        return Math.max(
                1, (int) Math.ceil(Math.log(minimumAngle / angularError) / Math.log(retention)));
    }

    public record AimForecast(
            boolean visible,
            double angularError,
            int ticksUntilAligned,
            double confidence,
            Vec3 aimPoint,
            double inputMultiplier) {
        public static AimForecast unavailable() {
            return new AimForecast(false, 180.0D, Integer.MAX_VALUE, 0.0D, null, 0.0D);
        }
    }

    /**
     * While our eye is inside the opponent, every horizontal look direction
     * still begins inside their hitbox. Use that safe interval to turn toward
     * the side from which we will look back after crossing. Without this
     * preview the geometric yaw flips only after passing the box centre, so a
     * far landing leaves almost the complete 180-degree turn for later ticks.
     */
    public static double crossingLookaheadTicks(
            Vec3 localVelocity,
            Vec3 targetVelocity,
            Vec3 eye,
            AxisAlignedBB box,
            double exitMargin) {
        double relativeX = localVelocity.xCoord - targetVelocity.xCoord;
        double relativeZ = localVelocity.zCoord - targetVelocity.zCoord;
        if (relativeX * relativeX + relativeZ * relativeZ < CROSSING_MIN_RELATIVE_SPEED_SQUARED)
            return 0.0D;

        AxisAlignedBB corridor = box.expand(exitMargin, exitMargin, exitMargin);
        double exitX = axisExitTicks(eye.xCoord, relativeX, corridor.minX, corridor.maxX);
        double exitZ = axisExitTicks(eye.zCoord, relativeZ, corridor.minZ, corridor.maxZ);
        double exitTicks = Math.min(exitX, exitZ);
        if (!Double.isFinite(exitTicks) || exitTicks < 0.0D) return 0.0D;
        return Mth.clamp(exitTicks + 0.65D, 0.75D, CROSSING_EXIT_LOOKAHEAD_TICKS);
    }

    private static double axisExitTicks(
            double position, double velocity, double minimum, double maximum) {
        if (Math.abs(velocity) < 1.0E-6D) return Double.POSITIVE_INFINITY;
        return velocity > 0.0D
                ? Math.max(0.0D, (maximum - position) / velocity)
                : Math.max(0.0D, (minimum - position) / velocity);
    }
}
