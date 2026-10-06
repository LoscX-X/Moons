package com.blanoir.moons.client.utils.rotation.aim;

import com.blanoir.moons.client.compat.math.Mth;
import com.blanoir.moons.client.utils.math.MathUtils;
import com.blanoir.moons.client.utils.prediction.AimPrediction;
import com.blanoir.moons.client.utils.raytrace.RaytraceUtils;
import com.blanoir.moons.client.utils.rotation.Rotation;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.Vec3;

/**
 * C: Existing FullLock horizontal lead constrained to the live hitbox.
 * Updates caller-owned angles/velocities and clears the original crossing/orbit flags.
 * Reads the predictor supplied in AimState; no random sampling. Packet-cadence stepping
 * remains in the packet smoother. Invoke only after the original frame observation step.
 */
public final class FullLockAimSolver {
    private FullLockAimSolver() {}

    public static void solve(
            AimState state,
            AimParameters parameters,
            Minecraft client,
            EntityLivingBase target,
            Vec3 point,
            double rawDelta) {
        double delta = Mth.clamp(rawDelta, 1.0D / 1000.0D, 1.0D / 20.0D);
        float previousYaw = state.yaw;
        float previousPitch = state.pitch;
        Vec3 leadPoint = fullLockLeadPoint(state, parameters, client, target, point, delta);
        Rotation desired = AimAngles.rotationTo(client.thePlayer.getPositionEyes(1F), leadPoint);
        state.yaw += MathUtils.wrappedAngleDifference(state.yaw, desired.yaw());
        state.pitch = Mth.clamp(desired.pitch(), -90.0F, 90.0F);
        state.yawVelocity =
                MathUtils.wrappedAngleDifference(previousYaw, state.yaw) / (float) delta;
        state.pitchVelocity = (state.pitch - previousPitch) / (float) delta;
        state.crossingTarget = false;
        state.pathJitterBlend = 0.0F;
        state.heldOrbitOffset = null;
    }

    public static Vec3 fullLockLeadPoint(
            AimState state,
            AimParameters parameters,
            Minecraft client,
            EntityLivingBase target,
            Vec3 point,
            double deltaSeconds) {
        double leadTicks = parameters.fullLockPrediction();
        if (leadTicks <= 0.0D) return point;
        Vec3 travel = state.prediction.displacement(leadTicks);

        AxisAlignedBB box = target.getEntityBoundingBox();
        // Preserve angular room for quantization at the outer edge of reach.
        double insetX = Math.min((box.maxX - box.minX) * 0.22D, 0.11D);
        double insetZ = Math.min((box.maxZ - box.minZ) * 0.22D, 0.11D);
        Vec3 predicted =
                AimPrediction.clampedLead(
                        point,
                        new Vec3(travel.xCoord, 0.0D, travel.zCoord),
                        new AxisAlignedBB(
                                box.minX + insetX,
                                point.yCoord,
                                box.minZ + insetZ,
                                box.maxX - insetX,
                                point.yCoord,
                                box.maxZ - insetZ));
        Vec3 eye = client.thePlayer.getPositionEyes(1F);
        return RaytraceUtils.canRayTraceTo(client, eye, predicted, parameters.throughBlocks())
                ? predicted
                : point;
    }
}
