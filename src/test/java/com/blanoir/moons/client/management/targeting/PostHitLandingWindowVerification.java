package com.blanoir.moons.client.management.targeting;

import net.minecraft.world.phys.Vec3;

/** Replays repeated Aura attacks around the airborne-to-ground transition. */
public final class PostHitLandingWindowVerification {
    private static int checks;

    public static void main(String[] args) {
        repeatedAttacks();
        lifecycle();
        System.out.println("Post-hit landing verification passed: " + checks + " assertions");
    }

    private static void repeatedAttacks() {
        PostHitLandingWindow window = new PostHitLandingWindow();
        window.arm(sample(1, 0, true), 24, 5);
        check(
                !window.update(sample(1, 1, true), Vec3.ZERO).insideLandingWindow(),
                "ordinary grounded motion cannot trigger a landing");
        check(
                window.update(sample(1, 2, false), Vec3.ZERO).sawAirborne(),
                "airborne motion is recorded");
        window.arm(sample(1, 3, false), 24, 5);
        window.update(sample(1, 3, false), Vec3.ZERO);
        // Attack callback runs after the remote entity landed but before this
        // tick's placement update: this used to erase previousOnGround=false.
        window.arm(sample(1, 4, true), 24, 5);
        var landed = window.update(sample(1, 4, true), new Vec3(.1D, 0, 0));
        check(landed.insideLandingWindow(), "same-tick Aura attack preserves landing edge");
        check(
                Math.abs(landed.targetHorizontalSpeed() - .2D) < 1.0E-8D,
                "rearming preserves the previous position sample");
        check(
                Math.abs(landed.relativeHorizontalSpeed() - .1D) < 1.0E-8D,
                "relative speed uses measured displacement");
        check(
                window.update(sample(1, 4, true), Vec3.ZERO).insideLandingWindow(),
                "duplicate updates cannot consume the window");
        for (int tick = 5; tick < 9; tick++) {
            window.arm(sample(1, tick, true), 24, 5);
            check(
                    window.update(sample(1, tick, true), Vec3.ZERO).insideLandingWindow(),
                    "repeated attacks keep the existing landing opportunity");
        }
        window.arm(sample(1, 9, true), 24, 5);
        check(
                window.update(sample(1, 9, true), Vec3.ZERO).expired(),
                "attacks cannot extend the landing window indefinitely");
        window.arm(sample(1, 10, true), 24, 5);
        check(
                !window.update(sample(1, 11, true), Vec3.ZERO).insideLandingWindow(),
                "a fresh expired request still needs a new landing");
        window.update(sample(1, 12, false), Vec3.ZERO);
        var landedBeforeAttack = window.update(sample(1, 13, true), Vec3.ZERO);
        window.arm(sample(1, 13, true), 24, 5);
        check(
                landedBeforeAttack.insideLandingWindow()
                        && window.update(sample(1, 13, true), Vec3.ZERO).insideLandingWindow(),
                "attack after the landing update preserves the open window too");
    }

    private static void lifecycle() {
        PostHitLandingWindow window = new PostHitLandingWindow();
        window.arm(sample(1, 0, false), 4, 3);
        check(
                window.update(sample(1, 1, true), Vec3.ZERO).insideLandingWindow(),
                "arming airborne can observe the following landing");
        window.arm(sample(2, 1, true), 4, 3);
        check(
                !window.update(sample(2, 2, true), Vec3.ZERO).sawAirborne(),
                "target changes cannot inherit another target's airborne sample");
        check(
                window.update(sample(2, 6, true), Vec3.ZERO).expired(),
                "skipped ticks expire the lifetime");
        window.arm(sample(2, 10, false), 10, 3);
        check(
                window.update(sample(2, 9, true), Vec3.ZERO).expired(),
                "backwards time invalidates the old world track");
        window.arm(sample(2, 11, false), 10, 3);
        window.clear();
        check(!window.update(sample(2, 12, true), Vec3.ZERO).armed(), "clear releases the request");
    }

    private static PostHitLandingWindow.Sample sample(int id, int tick, boolean grounded) {
        return new PostHitLandingWindow.Sample(
                id, tick, new Vec3(tick * .2D, grounded ? 0 : .3D, 0), grounded);
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
