package com.blanoir.moons.client.module.impl.combat.silentaura;

import com.blanoir.moons.client.compat.math.VecMath;
import com.blanoir.moons.client.utils.rotation.aim.AimGeometry;

import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.Vec3;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;
import java.util.function.Predicate;

/** Optional point effects after the existing Center/Closest policy, before rotation planning. */
final class SilentAuraPointProcessor {
    record Parameters(
            boolean lazy,
            double lazyThreshold,
            boolean gaussian,
            double horizontalDeviation,
            double verticalDeviation,
            double response,
            int intervalTicks) {
        boolean active() {
            return lazy || gaussian;
        }
    }

    private final DoubleSupplier gaussianSample;
    private Object world;
    private Object target;
    private Parameters parameters;
    private AxisAlignedBB lastBox;
    private int lastTick = Integer.MIN_VALUE;
    private long nextSampleTick;
    private Vec3 heldFractions;
    private Vec3 offset = VecMath.ZERO;
    private Vec3 targetOffset = VecMath.ZERO;

    SilentAuraPointProcessor() {
        this(() -> ThreadLocalRandom.current().nextGaussian());
    }

    SilentAuraPointProcessor(DoubleSupplier gaussianSample) {
        this.gaussianSample = gaussianSample;
    }

    /**
     * State advances once per logical tick. Repeated reads still project onto the live box and
     * validate geometry, so movement or a newly placed wall cannot preserve a stale world point.
     * The caller supplies an already validated base point and the final visibility/range predicate.
     */
    Vec3 process(
            Object world,
            Object target,
            int tick,
            AxisAlignedBB box,
            Vec3 preferred,
            Parameters parameters,
            Predicate<Vec3> usable) {
        if (preferred == null || !parameters.active()) {
            reset();
            return preferred;
        }
        long elapsed = (long) tick - lastTick;
        if (this.world != world
                || this.target != target
                || !parameters.equals(this.parameters)
                || elapsed < 0
                || elapsed > 20
                || lastBox != null
                        && VecMath.center(lastBox).squareDistanceTo(VecMath.center(box)) > 64.0D) {
            reset();
            this.world = world;
            this.target = target;
            this.parameters = parameters;
        }
        boolean newTick = lastTick != tick;
        Vec3 base = preferred;
        if (parameters.lazy()) {
            Vec3 held = heldFractions == null ? null : project(box, heldFractions);
            if (held == null
                    || !usable.test(held)
                    || newTick
                            && held.squareDistanceTo(preferred)
                                    >= parameters.lazyThreshold() * parameters.lazyThreshold()) {
                heldFractions = fractions(box, preferred);
                held = preferred;
            }
            base = held;
        }
        if (newTick && parameters.gaussian()) {
            if (lastTick == Integer.MIN_VALUE || tick >= nextSampleTick) {
                targetOffset =
                        new Vec3(
                                sample(parameters.horizontalDeviation()),
                                sample(parameters.verticalDeviation()),
                                sample(parameters.horizontalDeviation()));
                nextSampleTick = (long) tick + Math.max(1, parameters.intervalTicks());
            }
            long ticks = lastTick == Integer.MIN_VALUE ? 1 : Math.max(1L, (long) tick - lastTick);
            double blend = 1.0D - Math.pow(1.0D - parameters.response(), ticks);
            offset = VecMath.lerp(offset, targetOffset, blend);
        }
        lastTick = tick;
        lastBox = box;

        // Offset the point, never the target's hitbox. Geometry can override optional effects.
        Vec3 candidate = parameters.gaussian() ? clamp(box, base.add(offset)) : base;
        if (usable.test(candidate)) return candidate;
        if (usable.test(base)) return base;
        if (usable.test(preferred)) {
            heldFractions = fractions(box, preferred);
            return preferred;
        }
        return null;
    }

    void reset() {
        world = target = null;
        parameters = null;
        lastBox = null;
        lastTick = Integer.MIN_VALUE;
        nextSampleTick = 0L;
        heldFractions = null;
        offset = targetOffset = VecMath.ZERO;
    }

    private double sample(double deviation) {
        if (deviation <= 0.0D) return 0.0D;
        // Bound the Gaussian tails before clipping the final point to the real box.
        return Math.clamp(gaussianSample.getAsDouble(), -3.0D, 3.0D) * deviation;
    }

    private static Vec3 fractions(AxisAlignedBB box, Vec3 point) {
        return new Vec3(
                AimGeometry.fraction(point.xCoord, box.minX, box.maxX),
                AimGeometry.fraction(point.yCoord, box.minY, box.maxY),
                AimGeometry.fraction(point.zCoord, box.minZ, box.maxZ));
    }

    private static Vec3 project(AxisAlignedBB box, Vec3 fractions) {
        return AimGeometry.localPoint(box, fractions.xCoord, fractions.yCoord, fractions.zCoord);
    }

    private static Vec3 clamp(AxisAlignedBB box, Vec3 point) {
        return new Vec3(
                Math.clamp(point.xCoord, box.minX, box.maxX),
                Math.clamp(point.yCoord, box.minY, box.maxY),
                Math.clamp(point.zCoord, box.minZ, box.maxZ));
    }
}
