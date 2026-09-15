package com.blanoir.moons.client.utils.rotation.aim;

import com.blanoir.moons.client.utils.world.placement.BlockPlacementUtils;
import com.blanoir.moons.client.utils.world.placement.FaceScanA;
import com.blanoir.moons.client.utils.world.placement.PlacementRaycast;

import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

/**
 * J: AntiLava point-selection policy, sharing FaceScanA traversal with Scaffold.
 * Rejects out-of-range requested points before angle calculation, traces unquantized angles,
 * then ranks actual hit distance plus the original linear center bias. No history or sampling.
 */
public final class AimPointsJ {
    private AimPointsJ() {}

    public static FaceScanA.Result scan(
            PlacementRaycast rays,
            Minecraft client,
            BlockTarget target,
            Vec3 eye,
            double range,
            double[] offsets,
            double bestScore) {
        return FaceScanA.scan(
                offsets,
                (u, v) ->
                        BlockPlacementUtils.facePoint(
                                client, target.support(), target.face(), u, v),
                requested -> {
                    if (eye.distanceToSqr(requested) > range * range) return null;
                    return AimSolverD.rotationTo(eye, requested);
                },
                rotation ->
                        rays.traceFace(
                                client,
                                eye,
                                rotation.yaw(),
                                rotation.pitch(),
                                range,
                                target.support(),
                                target.face()),
                sample ->
                        eye.distanceToSqr(sample.hit().getLocation())
                                + Math.abs(sample.u() - 0.5D) * 0.02D
                                + Math.abs(sample.v() - 0.5D) * 0.02D,
                bestScore);
    }
}
