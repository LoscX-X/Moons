package com.blanoir.moons.client.utils.rotation.aim;

import com.blanoir.moons.client.utils.rotation.Rotation;
import com.blanoir.moons.client.utils.rotation.quantize.QuantizerA;
import com.blanoir.moons.client.utils.world.placement.BlockPlacementUtils;
import com.blanoir.moons.client.utils.world.placement.FaceScanA;
import com.blanoir.moons.client.utils.world.placement.PlacementRaycast;

import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

/**
 * G: Scaffold face-point policy. Selects the least-turn visible hit after the original
 * yaw/pitch quantization, with a small squared center bias. Standard/Center/Dense differ
 * only in their sample grids; Adaptive retries Dense only after Center returns no hit.
 * FaceScanA owns traversal; AimSolverE owns angle solving. No module state or settings reads.
 * Other block interactions may reuse this policy when these reach, quantization and ranking
 * rules match their needs. Switching another policy to G is a behavior change.
 */
public final class AimPointsG {
    private AimPointsG() {}

    private static final double[] PRIMARY_FACE_OFFSETS = {
        0.5D, 0.375D, 0.625D, 0.25D, 0.75D, 0.125D, 0.875D, 0.0625D, 0.9375D
    };
    private static final double[] STANDARD_FACE_OFFSETS = {
        0.03125D, 0.09375D, 0.15625D, 0.21875D,
        0.28125D, 0.34375D, 0.40625D, 0.46875D,
        0.53125D, 0.59375D, 0.65625D, 0.71875D,
        0.78125D, 0.84375D, 0.90625D, 0.96875D
    };
    private static final double[] DENSE_FACE_OFFSETS = {
        0.015625D, 0.03125D, 0.09375D, 0.15625D, 0.21875D, 0.28125D, 0.34375D, 0.40625D, 0.46875D,
        0.53125D, 0.59375D, 0.65625D, 0.71875D, 0.78125D, 0.84375D, 0.90625D, 0.96875D, 0.984375D
    };

    public enum Sampling {
        STANDARD,
        CENTER,
        DENSE,
        ADAPTIVE
    }

    public static BlockAim resolve(
            PlacementRaycast rays,
            Sampling sampling,
            Minecraft client,
            BlockTarget target,
            Vec3 eye,
            Rotation base,
            QuantizerA.Adapter quantizer) {
        return switch (sampling) {
            case STANDARD ->
                    scan(rays, client, target, eye, base, STANDARD_FACE_OFFSETS, quantizer);
            case CENTER -> scan(rays, client, target, eye, base, PRIMARY_FACE_OFFSETS, quantizer);
            case DENSE -> scan(rays, client, target, eye, base, DENSE_FACE_OFFSETS, quantizer);
            case ADAPTIVE -> {
                BlockAim primary =
                        scan(rays, client, target, eye, base, PRIMARY_FACE_OFFSETS, quantizer);
                yield primary != null
                        ? primary
                        : scan(rays, client, target, eye, base, DENSE_FACE_OFFSETS, quantizer);
            }
        };
    }

    public static BlockAim scan(
            PlacementRaycast rays,
            Minecraft client,
            BlockTarget target,
            Vec3 eye,
            Rotation base,
            double[] offsets,
            QuantizerA.Adapter quantizer) {
        double range = client.player.blockInteractionRange();
        FaceScanA.Result best =
                FaceScanA.scanPrimitive(
                        offsets,
                        (u, v) ->
                                BlockPlacementUtils.facePoint(
                                        client, target.support(), target.face(), u, v),
                        point -> AimSolverE.solve(eye, point, base, quantizer),
                        rotation ->
                                rays.traceFace(
                                        client,
                                        eye,
                                        rotation.yaw(),
                                        rotation.pitch(),
                                        range,
                                        target.support(),
                                        target.face()),
                        sample -> score(sample, base),
                        Double.MAX_VALUE);
        return best == null
                ? null
                : new BlockAim(target, best.sample().rotation(), best.sample().hit());
    }

    public static double score(FaceScanA.Sample sample, Rotation base) {
        double angleScore = AimSolverE.distance(sample.rotation(), base.yaw(), base.pitch());
        double centerScore =
                (sample.u() - 0.5D) * (sample.u() - 0.5D)
                        + (sample.v() - 0.5D) * (sample.v() - 0.5D);
        return angleScore + centerScore * 0.01D;
    }
}
