package com.blanoir.moons.client.module.impl.combat.silentaura;

import com.blanoir.moons.client.module.impl.combat.silentaura.aim.BalanceSilentAimType;
import com.blanoir.moons.client.utils.rotation.Rotation;
import com.blanoir.moons.client.utils.rotation.aim.AimJitter;
import com.blanoir.moons.client.utils.rotation.aim.AimMotionNoise;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Method;

/** Behavioral checks using the real shared/client classes; no game session required. */
public final class SilentAuraVerification {
    private static int checks;

    public static void main(String[] args) throws Exception {
        prediction();
        noise();
        balance();
        packets();
        System.out.println("SilentAura verification passed: " + checks + " assertions");
    }

    private static void prediction() {
        SilentAuraPrediction prediction = new SilentAuraPrediction();
        Vec3 velocity = new Vec3(0.2D, 0.0D, 0.0D);
        prediction.observe(0, Vec3.ZERO, velocity);
        near(prediction.displacement(1.0D).x, 0.2D, 1.0E-8D, "one tick lead uses blocks/tick");
        prediction.observe(0, new Vec3(100, 0, 0), Vec3.ZERO);
        near(prediction.displacement(1).x, 0.2D, 1.0E-8D, "render calls do not resample a tick");
        prediction.observe(1, velocity, velocity);
        prediction.observe(2, velocity, Vec3.ZERO);
        near(prediction.displacement(2).length(), 0, 1.0E-8D, "stop discards momentum");
        prediction.observe(3, Vec3.ZERO, velocity.scale(-1));
        check(prediction.displacement(1).x < 0, "reversal predicts new direction");
        prediction.observe(4, new Vec3(100, 0, 0), velocity);
        near(prediction.displacement(2).length(), 0, 1.0E-8D, "teleport does not create lead");
        prediction.reset();
        near(prediction.displacement(2).length(), 0, 1.0E-8D, "reset discards prediction");
        prediction.observe(9, Vec3.ZERO, velocity);
        near(prediction.displacement(0).length(), 0, 1.0E-8D, "disabled lead stays disabled");
        check(prediction.displacement(0.575D).x > prediction.displacement(0.425D).x,
                "Lock horizon is stronger than Balance horizon");
    }

    private static void noise() {
        AABB box = new AABB(-0.3D, 0, -0.3D, 0.3D, 1.8D, 0.3D);
        Vec3 eye = new Vec3(0, 1.6D, -3);
        Vec3 base = new Vec3(0, 1.5D, 0);
        Vec3 edge = new Vec3(box.minX, 1.5D, 0);
        check(AimJitter.insideHitbox(eye, edge, box, 0, 7L, 1.0E-5D, 1)
                .distanceTo(edge) < 1.0E-5D, "near-zero strength cannot snap a surface point inward");
        for (double strength : new double[]{0, 0.01D, 0.38D, 0.75D, 1}) {
            for (double speed : new double[]{0.1D, 0.85D, 1, 3}) {
                for (long seed = 1; seed <= 16; seed++) {
                    Vec3 previous = null;
                    for (int frame = 0; frame < 240; frame++) {
                        double time = frame / 120.0D;
                        Vec3 point = AimJitter.insideHitbox(eye, base, box, time, seed, strength, speed);
                        check(box.contains(point), "noise remains inside the hitbox");
                        check(point.distanceTo(base) <= 0.07D, "noise amplitude is bounded");
                        if (strength == 0) check(point.equals(base), "zero strength is exactly neutral");
                        if (previous != null) check(point.distanceTo(previous) < 2.0D / 120.0D,
                                "noise has no frame discontinuity");
                        previous = point;
                    }
                }
            }
        }
        for (int index = -20; index <= 20; index++) {
            double left = AimMotionNoise.sample(index - 1.0E-5D, 7L);
            double right = AimMotionNoise.sample(index + 1.0E-5D, 7L);
            near(left, right, 1.0E-7D, "noise remains continuous at lattice boundaries");
        }
        check(AimMotionNoise.sample(0.41D, 1L) != AimMotionNoise.sample(0.41D, 2L),
                "independent seeds produce independent paths");
    }

    private static void balance() throws Exception {
        BalanceSilentAimType profile = new BalanceSilentAimType();
        profile.correct(new Rotation(10, 0), 0, 0, true);
        Rotation moved = new Rotation(12, 18);
        check(profile.correct(moved, 0, 0.001D, false).rotation().equals(moved),
                "Balance follows fresh horizontal and vertical geometry");
        double scale = profile.correct(moved, 0, 0.01D, false).responseScale();
        near(profile.correct(moved, 0, 0.01D, false).responseScale(), scale, 0,
                "extra render calls cannot redraw response at the same time");
        Method step = SilentAuraRotationController.class.getDeclaredMethod("stepToward",
                Rotation.class, double.class, double.class, double.class, double.class,
                double.class, double.class);
        step.setAccessible(true);
        for (int fps : new int[]{30, 60, 144, 1000, 2000}) {
            SilentAuraRotationController controller = new SilentAuraRotationController(false);
            controller.beginFrom(0, 0);
            for (int frame = 0; frame < fps * 2; frame++) {
                step.invoke(controller, new Rotation(90, 30), 1.0D / fps,
                        21.28D, 660.0D, 440.0D, 1.0D, 1.0D);
            }
            near(controller.yaw(), 90, 0.2D, "Balance converges at " + fps + " FPS");
            near(controller.pitch(), 30, 0.2D, "Balance pitch converges at " + fps + " FPS");
            float before = controller.yaw();
            step.invoke(controller, new Rotation(-90, 30), 0.0D,
                    21.28D, 660.0D, 440.0D, 1.0D, 1.0D);
            near(controller.yaw(), before, 0, "zero elapsed time cannot move aim");
            controller.clear();
            check(!controller.active() && controller.targetId() == -1, "clear releases controller state");
        }
    }

    private static void packets() {
        for (boolean lock : new boolean[]{false, true}) {
            for (boolean matrix : new boolean[]{false, true}) {
                PacketRotationSmoother smoother = new PacketRotationSmoother(lock);
                float yaw = 0;
                for (int tick = 0; tick < 60; tick++) {
                    Rotation sample = smoother.sample(tick, 0, 0, 120, 0, 0.5D, false, matrix);
                    check(smoother.sample(tick, 40, 30, -80, 35, 0.5D, true, matrix).equals(sample),
                            "movement and attack reuse one tick sample");
                    check(Math.abs(sample.yaw() - yaw) <= 48.001D, "packet yaw speed bound");
                    near(sample.pitch(), 0, 0, "level tracking does not synthesize pitch counts");
                    smoother.confirm(sample.yaw(), sample.pitch());
                    yaw = sample.yaw();
                }
                near(yaw, 120, 0.1D, "packet tracking converges");
                smoother.rebase(300, 0);
                Rotation rebased = smoother.sample(61, 0, 0, -50, 0, 0.5D, false, matrix);
                check(rebased.yaw() >= 300 && rebased.yaw() <= 310.1D,
                        "preemption recovery uses observed continuous yaw");
            }
        }
        PacketRotationSmoother full = new PacketRotationSmoother(true, true);
        check(full.sample(0, 0, 0, 0.4F, 0, 0.5D, false, false).yaw() > 0,
                "Full Lock corrects sub-degree misses");
        full.rebase(0, 0);
        Rotation capped = full.sample(1, 0, 0, 150, 60, 0.5D, false, false, 30, 0);
        near(capped.yaw(), 30, 0, "Full Lock honors configured angle step");
        near(capped.pitch(), 30, 0, "Full Lock uses exact zero smoothing");
    }

    private static void near(double actual, double expected, double tolerance, String message) {
        check(Double.isFinite(actual) && Math.abs(actual - expected) <= tolerance,
                message + ": " + actual + " expected " + expected);
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
