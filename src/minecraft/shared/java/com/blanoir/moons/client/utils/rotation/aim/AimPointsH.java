package com.blanoir.moons.client.utils.rotation.aim;

import com.blanoir.moons.client.utils.rotation.Rotation;
import com.blanoir.moons.client.utils.rotation.quantize.QuantizerA;
import com.blanoir.moons.client.utils.world.placement.BlockPlacementUtils;
import com.blanoir.moons.client.utils.world.placement.PlacementRaycast;

import net.minecraft.client.Minecraft;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * H: GodBridge side-face selection. Tries the preferred ray first, then ordered yaw/height
 * probes while retaining the bridge direction. Horizontal faces only; first valid probe wins.
 * A separate turn-recovery pass samples real side interiors only after compass rays fail.
 * Every chosen ray is re-traced. Caller supplies quantization; no movement history is retained.
 */
public final class AimPointsH {
    private AimPointsH() {}

    private static final float[] YAW_OFFSETS = {0, .5F, -.5F, 1, -1, 2, -2, 4, -4, 8, -8, 12, -12};
    private static final double[] HEIGHTS = {.75, .5, .9, .25, .1};
    private static final double[] TURN_SIDES = {.5, .25, .75, .1, .9};

    public static BlockAim resolve(
            PlacementRaycast rays,
            Minecraft client,
            BlockTarget target,
            Vec3 eye,
            Rotation preferred,
            double range,
            QuantizerA.Adapter quantizer) {
        return resolve(rays, client, target, eye, preferred, range, quantizer, false);
    }

    public static BlockAim resolve(
            PlacementRaycast rays,
            Minecraft client,
            BlockTarget target,
            Vec3 eye,
            Rotation preferred,
            double range,
            QuantizerA.Adapter quantizer,
            boolean turnRecovery) {
        Predicate<Rotation> reachable =
                candidate ->
                        insideFace(
                                rays.traceFace(
                                        client,
                                        eye,
                                        candidate.yaw(),
                                        candidate.pitch(),
                                        range,
                                        target.support(),
                                        target.face()),
                                target);
        BiFunction<Double, Double, Vec3> point =
                (side, height) ->
                        BlockPlacementUtils.facePoint(
                                client, target.support(), target.face(), side, height);
        Rotation rotation =
                turnRecovery
                        ? turnAim(eye, preferred, target.face(), point, quantizer, reachable)
                        : sideAim(
                                eye,
                                preferred,
                                target.face(),
                                height -> point.apply(.5, height),
                                raw -> quantizer.relative(preferred, raw),
                                reachable);
        if (rotation == null) return null;
        BlockHitResult hit =
                rays.traceFace(
                        client,
                        eye,
                        rotation.yaw(),
                        rotation.pitch(),
                        range,
                        target.support(),
                        target.face());
        return insideFace(hit, target) ? new BlockAim(target, rotation, hit) : null;
    }

    /** Side-face fallback for a direction change; keeps the closest validated downward look. */
    public static Rotation turnAim(
            Vec3 eye,
            Rotation preferred,
            Direction face,
            BiFunction<Double, Double, Vec3> facePoint,
            QuantizerA.Adapter quantizer,
            Predicate<Rotation> reachable) {
        if (face.getAxis() == Direction.Axis.Y) return null;
        Rotation best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        for (double side : TURN_SIDES) {
            for (double height : HEIGHTS) {
                Rotation candidate =
                        AimSolverE.solve(eye, facePoint.apply(side, height), preferred, quantizer);
                if (candidate == null || candidate.pitch() <= 0 || candidate.pitch() >= 90)
                    continue;
                double score = AimSolverE.distance(candidate, preferred.yaw(), preferred.pitch());
                if (score < bestScore && reachable.test(candidate)) {
                    best = candidate;
                    bestScore = score;
                }
            }
        }
        return best;
    }

    /** A perfect diagonal can hit the shared edge of two faces; choose a small interior margin. */
    public static boolean insideFace(BlockHitResult hit, BlockTarget target) {
        if (hit == null) return false;
        double side =
                target.face().getAxis() == Direction.Axis.X
                        ? hit.getLocation().z - target.support().getZ()
                        : hit.getLocation().x - target.support().getX();
        return side >= .015 && side <= .985;
    }

    private static Rotation sideAim(
            Vec3 eye,
            Rotation preferred,
            Direction face,
            Function<Double, Vec3> facePoint,
            UnaryOperator<Rotation> quantize,
            Predicate<Rotation> reachable) {
        if (face.getAxis() == Direction.Axis.Y) return null;
        Rotation fixed = quantize.apply(preferred);
        if (reachable.test(fixed)) return fixed;
        Vec3 center = facePoint.apply(.5);
        for (float offset : YAW_OFFSETS) {
            float yaw =
                    quantize.apply(new Rotation(preferred.yaw() + offset, preferred.pitch())).yaw();
            Vec3 direction = Vec3.directionFromRotation(0, yaw);
            double component = face.getAxis() == Direction.Axis.X ? direction.x : direction.z;
            double normal = face.getAxis() == Direction.Axis.X ? face.getStepX() : face.getStepZ();
            if (component * normal >= -1.0E-6) continue;
            double distance =
                    (face.getAxis() == Direction.Axis.X ? center.x - eye.x : center.z - eye.z)
                            / component;
            if (distance <= 1.0E-5) continue;
            for (double height : HEIGHTS) {
                Vec3 point = facePoint.apply(height);
                float pitch = (float) Math.toDegrees(Math.atan2(eye.y - point.y, distance));
                if (pitch <= 0 || pitch >= 90) continue;
                Rotation candidate = quantize.apply(new Rotation(yaw, pitch));
                if (reachable.test(candidate)) return candidate;
            }
        }
        return null;
    }
}
