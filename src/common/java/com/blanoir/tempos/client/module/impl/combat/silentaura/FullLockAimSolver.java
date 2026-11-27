package com.blanoir.tempos.client.module.impl.combat.silentaura;

import com.blanoir.tempos.client.utils.math.AngleMath;
import com.blanoir.tempos.client.utils.prediction.AimPrediction;
import com.blanoir.tempos.client.utils.raytrace.LineOfSight;
import com.blanoir.tempos.client.utils.rotation.Rotation;
import com.blanoir.tempos.client.utils.rotation.aim.AimAngles;
import com.blanoir.tempos.client.utils.rotation.aim.AimParameters;
import com.blanoir.tempos.client.utils.rotation.aim.AimState;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Existing FullLock horizontal lead constrained to the live hitbox.
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
            LivingEntity target,
            Vec3 point,
            double rawDelta) {
        double delta = Math.clamp(rawDelta, 1.0D / 1000.0D, 1.0D / 20.0D);
        float previousYaw = state.yaw;
        float previousPitch = state.pitch;
        Vec3 leadPoint = fullLockLeadPoint(state, parameters, client, target, point, delta);
        Rotation desired = AimAngles.rotationTo(client.player.getEyePosition(), leadPoint);
        state.yaw += AngleMath.wrappedAngleDifference(state.yaw, desired.yaw());
        state.pitch = Math.clamp(desired.pitch(), -90.0F, 90.0F);
        state.yawVelocity =
                AngleMath.wrappedAngleDifference(previousYaw, state.yaw) / (float) delta;
        state.pitchVelocity = (state.pitch - previousPitch) / (float) delta;
        state.crossingTarget = false;
        state.pathJitterBlend = 0.0F;
        state.heldOrbitOffset = null;
    }

    public static Vec3 fullLockLeadPoint(
            AimState state,
            AimParameters parameters,
            Minecraft client,
            LivingEntity target,
            Vec3 point,
            double deltaSeconds) {
        double leadTicks = parameters.fullLockPrediction();
        if (leadTicks <= 0.0D) return point;
        Vec3 travel = state.prediction.displacement(leadTicks);

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
        return LineOfSight.canRayTraceTo(client, eye, predicted, parameters.throughBlocks())
                ? predicted
                : point;
    }
}
