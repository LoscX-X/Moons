package com.blanoir.tempos.client.module.impl.player.antilava;

import com.blanoir.tempos.client.manager.placement.PlacementRayPolicy;
import com.blanoir.tempos.client.utils.rotation.aim.AimAngles;
import com.blanoir.tempos.client.utils.rotation.aim.BlockTarget;
import com.blanoir.tempos.client.utils.world.placement.BlockPlacementUtils;
import com.blanoir.tempos.client.utils.world.placement.FaceGridScan;

import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

/**
 * AntiLava point-selection policy, sharing FaceGridScan traversal with Scaffold.
 * Rejects out-of-range requested points before angle calculation, traces unquantized angles,
 * then ranks actual hit distance plus the original linear center bias. No history or sampling.
 */
public final class LavaSupportFacePoints {
    private LavaSupportFacePoints() {}

    public static FaceGridScan.Result scan(
            PlacementRayPolicy rays,
            Minecraft client,
            BlockTarget target,
            Vec3 eye,
            double range,
            double[] offsets,
            double bestScore) {
        return FaceGridScan.scanPrimitive(
                offsets,
                (u, v) ->
                        BlockPlacementUtils.facePoint(
                                client, target.support(), target.face(), u, v),
                requested -> {
                    if (eye.distanceToSqr(requested) > range * range) return null;
                    return AimAngles.rotationTo(eye, requested);
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
