package com.blanoir.moons.client.module.impl.combat.silentaura;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Random;
import java.util.function.Predicate;

/** Geometry and state regression checks without a running game or network. */
public final class SilentAuraPointVerification {
    private static final Object WORLD = new Object();
    private static final Object TARGET = new Object();
    private static final AABB BOX = new AABB(0, 0, 0, 1, 2, 1);
    private static final Vec3 BASE = new Vec3(.5, 1, .5);
    private static final Predicate<Vec3> VISIBLE = point -> true;

    public static void main(String[] args) {
        disabledPreservesBase();
        lazyFollowsTheBox();
        visibilityOverridesEffects();
        independentAxesAndBounds();
        repeatedReadsDoNotAdvance();
        lifecycleResets();
        System.out.println("SilentAura AimPoint verification passed");
    }

    private static void disabledPreservesBase() {
        var processor =
                new SilentAuraPointProcessor(
                        () -> {
                            throw new AssertionError(
                                    "Disabled effects must not consume randomness");
                        });
        var disabled = parameters(false, false, .05, .025);
        require(run(processor, 0, BASE, disabled) == BASE, "Keep the original point instance");
        require(run(processor, 1, null, disabled) == null, "No base point means no output");
    }

    private static void lazyFollowsTheBox() {
        var processor = new SilentAuraPointProcessor(() -> 1.0D);
        var lazy = parameters(true, false, 0, 0);
        equal(run(processor, 0, BASE, lazy), BASE, "Initial anchor");
        equal(
                run(processor, 1, BASE.add(.05, 0, 0), lazy),
                BASE,
                "Ignore local changes below threshold");
        Vec3 moved = BASE.add(2, 0, 0);
        equal(
                processor.process(
                        WORLD, TARGET, 2, BOX.move(2, 0, 0), moved.add(.05, 0, 0), lazy, VISIBLE),
                moved,
                "Held anchor follows target translation immediately");
        AABB resized = new AABB(2, 0, 0, 4, 4, 2);
        Vec3 resizedCenter = resized.getCenter();
        equal(
                processor.process(WORLD, TARGET, 2, resized, resizedCenter, lazy, VISIBLE),
                resizedCenter,
                "Repeated read projects onto the live pose/box");
        Vec3 next = resizedCenter.add(.2, 0, 0);
        equal(
                processor.process(WORLD, TARGET, 3, resized, next, lazy, VISIBLE),
                next,
                "Threshold crossing accepts the new anchor immediately on its tick");
    }

    private static void visibilityOverridesEffects() {
        var processor = new SilentAuraPointProcessor(() -> 1.0D);
        var lazy = parameters(true, false, 0, 0);
        run(processor, 0, BASE, lazy);
        Vec3 exposed = BASE.add(.05, 0, 0);
        Predicate<Vec3> newlyVisible = point -> point.x > .52;
        equal(
                processor.process(WORLD, TARGET, 0, BOX, exposed, lazy, newlyVisible),
                exposed,
                "New wall invalidates held point even during the same tick");

        var gaussian = parameters(false, true, .1, 0);
        Predicate<Vec3> onlyBaseVisible = point -> point.distanceToSqr(BASE) < 1.0E-12;
        equal(
                processor.process(WORLD, TARGET, 1, BOX, BASE, gaussian, onlyBaseVisible),
                BASE,
                "Occluded offset falls back to validated base");
        Predicate<Vec3> withinReach = point -> point.distanceToSqr(Vec3.ZERO) <= BASE.lengthSqr();
        equal(
                processor.process(WORLD, TARGET, 2, BOX, BASE, gaussian, withinReach),
                BASE,
                "Offset cannot extend effective reach");
        require(
                processor.process(WORLD, TARGET, 3, BOX, BASE, gaussian, point -> false) == null,
                "No valid base or fallback cannot invent a point");
    }

    private static void independentAxesAndBounds() {
        var processor = new SilentAuraPointProcessor(() -> 100.0D);
        Vec3 horizontal = run(processor, 0, BASE, parameters(false, true, .1, 0));
        close(horizontal.y, BASE.y, "Zero vertical deviation stays zero");
        close(horizontal.x - BASE.x, .06, "Gaussian tail clipped to 3 sigma before interpolation");
        Vec3 vertical = run(processor, 1, BASE, parameters(false, true, 0, .1));
        close(vertical.x, BASE.x, "Zero horizontal deviation stays zero");
        require(vertical.y > BASE.y, "Vertical-only noise works");

        var bounds = parameters(false, true, .3, .2);
        AABB narrow = new AABB(.4, .99, .4, .6, 1.01, .6);
        for (int tick = 2; tick < 100; tick++) {
            Vec3 point = processor.process(WORLD, TARGET, tick, narrow, BASE, bounds, VISIBLE);
            require(
                    point.x >= narrow.minX
                            && point.x <= narrow.maxX
                            && point.y >= narrow.minY
                            && point.y <= narrow.maxY
                            && point.z >= narrow.minZ
                            && point.z <= narrow.maxZ,
                    "Point stays inside the unchanged real box");
        }
        Vec3 negative =
                run(
                        new SilentAuraPointProcessor(() -> -1),
                        0,
                        BASE,
                        parameters(false, true, .1, .1));
        require(
                negative.x < BASE.x && negative.y < BASE.y && negative.z < BASE.z,
                "Both signs are supported without a downward bias");
    }

    private static void repeatedReadsDoNotAdvance() {
        var once = new SilentAuraPointProcessor(new Random(1729)::nextGaussian);
        var often = new SilentAuraPointProcessor(new Random(1729)::nextGaussian);
        var both = parameters(true, true, .08, .03);
        for (int tick = 0; tick < 150; tick++) {
            Vec3 preferred = BASE.add(.05 * Math.sin(tick * .2), 0, 0);
            Vec3 expected = run(once, tick, preferred, both);
            for (int read = 0; read < 12; read++) {
                equal(
                        run(often, tick, preferred, both),
                        expected,
                        "Extra render/getter reads cannot resample or advance effects");
            }
        }
        int[] samples = {0};
        var cadence =
                new SilentAuraPointProcessor(
                        () -> {
                            samples[0]++;
                            return 1;
                        });
        var gaussian = parameters(false, true, .1, 0);
        for (int tick = 0; tick < 8; tick++) run(cadence, tick, BASE, gaussian);
        require(samples[0] == 2, "One horizontal sample pair per interval");
        run(cadence, 8, BASE, gaussian);
        require(samples[0] == 4, "Resample at the next interval");
    }

    private static void lifecycleResets() {
        var processor = new SilentAuraPointProcessor(() -> 1.0D);
        var gaussian = parameters(true, true, .1, .05);
        Vec3 initial = run(processor, 0, BASE, gaussian);
        for (int tick = 1; tick < 6; tick++) run(processor, tick, BASE, gaussian);
        equal(
                processor.process(WORLD, new Object(), 6, BOX, BASE, gaussian, VISIBLE),
                initial,
                "Replacement entity cannot inherit anchor or noise");
        equal(
                processor.process(new Object(), TARGET, 6, BOX, BASE, gaussian, VISIBLE),
                initial,
                "Replacement world cannot inherit history");
        run(processor, 7, BASE, gaussian);
        equal(run(processor, 0, BASE, gaussian), initial, "Tick rollback resets history");
        equal(run(processor, 100, BASE, gaussian), initial, "Long observation gap resets history");
        require(run(processor, 101, null, gaussian) == null, "Losing base clears effects");
        equal(run(processor, 102, BASE, gaussian), initial, "Reacquisition starts fresh");
        Vec3 moved = BASE.add(20, 0, 0);
        equal(
                processor.process(WORLD, TARGET, 103, BOX.move(20, 0, 0), moved, gaussian, VISIBLE),
                initial.add(20, 0, 0),
                "Teleport resets stale drift");
        Vec3 disabled = run(processor, 104, BASE, parameters(false, false, .1, .05));
        require(disabled == BASE, "Disabling removes both effects immediately");
    }

    private static SilentAuraPointProcessor.Parameters parameters(
            boolean lazy, boolean gaussian, double horizontal, double vertical) {
        return new SilentAuraPointProcessor.Parameters(
                lazy, .15, gaussian, horizontal, vertical, .2, 8);
    }

    private static Vec3 run(
            SilentAuraPointProcessor processor,
            int tick,
            Vec3 point,
            SilentAuraPointProcessor.Parameters parameters) {
        return processor.process(WORLD, TARGET, tick, BOX, point, parameters, VISIBLE);
    }

    private static void equal(Vec3 actual, Vec3 expected, String message) {
        require(
                actual != null && actual.distanceToSqr(expected) < 1.0E-20,
                message + ": expected " + expected + ", got " + actual);
    }

    private static void close(double actual, double expected, String message) {
        require(Math.abs(actual - expected) < 1.0E-10, message);
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
