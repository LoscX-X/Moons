package com.blanoir.moons.client.utils.world.placement;

import com.blanoir.moons.client.utils.rotation.Rotation;

import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;

/**
 * A: Ordered face-grid traversal. This is scan infrastructure, not an aim-point policy.
 * Currently used by Scaffold's AimPointsG and AntiLava's AimPointsJ.
 * Callers supply point geometry, angle resolution, hit validation and score. Null angle/hit
 * rejects a probe. Lower scores win; strict comparison preserves first-wins ties and the
 * supplied initial threshold, including the original MAX_VALUE versus POSITIVE_INFINITY.
 * No randomness, world ownership, configuration reads, sample reordering or implicit fallback.
 */
public final class FaceScanA {
    private FaceScanA() {}

    public record Sample(
            double u, double v, Vec3 requested, Rotation rotation, BlockHitResult hit) {}

    public record Result(Sample sample, double score) {}

    @FunctionalInterface
    public interface PointFactory {
        Vec3 apply(double u, double v);
    }

    public static Result scan(
            double[] offsets,
            BiFunction<Double, Double, Vec3> pointAt,
            Function<Vec3, Rotation> rotationAt,
            Function<Rotation, BlockHitResult> trace,
            ToDoubleFunction<Sample> score,
            double initialBestScore) {
        return scanPrimitive(
                offsets, (u, v) -> pointAt.apply(u, v), rotationAt, trace, score, initialBestScore);
    }

    /** The same traversal with primitive coordinates for high-frequency face scans. */
    public static Result scanPrimitive(
            double[] offsets,
            PointFactory pointAt,
            Function<Vec3, Rotation> rotationAt,
            Function<Rotation, BlockHitResult> trace,
            ToDoubleFunction<Sample> score,
            double initialBestScore) {
        Result best = null;
        double bestScore = initialBestScore;
        for (double u : offsets) {
            for (double v : offsets) {
                Vec3 point = pointAt.apply(u, v);
                Rotation rotation = rotationAt.apply(point);
                if (rotation == null) continue;
                BlockHitResult hit = trace.apply(rotation);
                if (hit == null) continue;
                Sample candidate = new Sample(u, v, point, rotation, hit);
                double candidateScore = score.applyAsDouble(candidate);
                if (candidateScore < bestScore) {
                    bestScore = candidateScore;
                    best = new Result(candidate, candidateScore);
                }
            }
        }
        return best;
    }
}
