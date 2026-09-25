package com.blanoir.moons.client.module.impl.combat.misplace;

import static com.blanoir.moons.client.module.impl.combat.misplace.MisplaceLatencyModel.*;
import static com.blanoir.moons.client.module.impl.combat.misplace.MisplaceSimulation.*;

import net.minecraft.world.phys.Vec3;

public final class MisplaceVerification {
    public static void main(String[] args) {
        causalGeometry();
        knockbackEvidence();
        lifecycleAndDisplay();
        independentTimelines();
        System.out.println(
                "Misplace verified: causal RTT geometry, FIFO attack origins, knockback phases and model bounds.");
    }

    private static Query query(
            double shown, double source, double current, int ping, int targetPing, long now) {
        return new Query(
                eye(source),
                now,
                eye(current),
                position(shown),
                box(shown),
                new Network(ping, targetPing, 50, 0, 600),
                3,
                now);
    }

    private static Observation observation(double x, double velocity, long now) {
        return new Observation(position(x), new Vec3(velocity, 0, 0), 0, now, 3, 400, -1, 0);
    }

    private static void causalGeometry() {
        var known = observation(3.7, -6, 1000);
        var low = estimate(known, query(4.1, 0, 0, 20, 250, 1000));
        var high = estimate(known, query(4.1, 0, 0, 150, 250, 1000));
        require(
                high.maximumDistance() < low.maximumDistance(),
                "A fast approaching target advances during OUR RTT");
        var differentOpponentPing = estimate(known, query(4.1, 0, 0, 150, 20, 1000));
        near(
                high.maximumDistance(),
                differentOpponentPing.maximumDistance(),
                "Target RTT must not be added twice to an already-received server snapshot");
        var stationary = observation(4, 0, 1000);
        var selfAlreadyMoved = estimate(stationary, query(4, 0, .5, 250, 20, 1000));
        require(
                pull(selfAlreadyMoved, eye(.5), box(4), .4) == 0,
                "Unsent local motion cannot be projected into the attack's server origin");
        var sentMovement = estimate(stationary, query(4, .5, .5, 250, 20, 1000));
        require(
                sentMovement.maximumDistance() < selfAlreadyMoved.maximumDistance(),
                "Actually sent motion changes the server origin");
        Query unknown =
                new Query(
                        null,
                        1000,
                        eye(0),
                        position(4),
                        box(4),
                        new Network(20, 20, 50, 0, 400),
                        3,
                        1000);
        require(
                estimate(known, unknown).verdict() == Verdict.NO_SOURCE,
                "Missing sent position cannot be replaced by current camera position");
        Query tooFar =
                new Query(
                        eye(0),
                        1000,
                        eye(0),
                        position(4),
                        box(4),
                        new Network(250, 20, 50, 0, 200),
                        3,
                        1000);
        require(
                estimate(known, tooFar).verdict() == Verdict.HORIZON_EXCEEDED,
                "Never clip a 300 ms arrival prediction to a fictitious 200 ms arrival");
        var unknownPing = estimate(known, query(4, 0, 0, -1, 20, 1000));
        require(!unknownPing.hasBounds(), "Absent ping is unknown, not a zero-latency link");
    }

    private static void knockbackEvidence() {
        MisplaceMotion track = new MisplaceMotion();
        track.reset(position(4), 800);
        track.observe(position(3.9), 850);
        track.observe(position(3.8), 900);
        track.impact(900, 250, 50, 0);
        var before = estimate(track.observation(true), query(4, 0, 0, 20, 250, 900));
        require(
                before.hasBounds(),
                "Before the victim round trip, pending knockback leaves the old server motion possible");
        var during = estimate(track.observation(true), query(4, 0, 0, 250, 250, 900));
        require(
                during.verdict() == Verdict.KNOCKBACK_TRANSITION,
                "An attack arriving across an unobserved impulse is uncertain");
        require(
                !estimate(track.observation(true), query(4, 0, 0, 20, 250, 1200)).hasBounds(),
                "Elapsed time without a movement report cannot confirm execution");
        track.observe(position(4.1), 1100);
        track.observe(position(4.3), 1250);
        track.observe(position(4.4), 1300);
        require(
                !estimate(track.observation(true), query(4.4, 0, 0, 20, 250, 1300)).hasBounds(),
                "One post-transition sample cannot establish the new velocity");
        track.observe(position(4.5), 1350);
        require(
                estimate(track.observation(true), query(4.5, 0, 0, 20, 250, 1350)).hasBounds(),
                "Fresh post-transition movement restores prediction without rewinding the victim");
    }

    private static void lifecycleAndDisplay() {
        MisplaceMotion track = new MisplaceMotion();
        track.reset(position(4), 1000);
        track.observe(position(3.7), 1050);
        track.observe(position(3.4), 1100);
        Query q = query(3.9, 0, 0, 80, 20, 1100);
        double amount = track.advance(q, .4, true, true, 0);
        require(amount > 0 && amount <= .4, "Useful correction remains bounded");
        var shifted = box(3.9).move(MisplaceMotion.offset(eye(0), position(3.9), amount));
        require(
                distance(eye(0), shifted) + 1e-6 >= track.estimate().maximumDistance(),
                "Displayed distance cannot be more optimistic than the model bound");
        for (int i = 0; i < 100; i++)
            near(
                    amount,
                    track.advance(q, .4, true, true, 60),
                    "Repeated frame reads cannot accumulate displacement");
        require(
                track.advance(query(3.9, 0, 0, 80, 20, 1351), .4, true, true, 0) == 0,
                "Stale network samples immediately release the estimate");
        track.discontinuity(position(2), 1400);
        require(
                track.advance(query(3.9, 0, 0, 80, 20, 1450), .4, true, true, 0) == 0,
                "Teleport interpolation is not closing motion");
        track.reset(position(4), 1500);
        near(
                .4,
                track.advance(query(4, 0, 0, 0, 0, 2000), .4, false, false, 0),
                "Fixed mode is explicit and independent of prediction availability");
        Vec3 eye = eye(0), end = eye.add(3, 0, 0);
        require(
                MisplaceMotion.intersection(box(3.6), eye, end) == null,
                "Unshifted box is beyond local reach");
        require(
                MisplaceMotion.intersection(box(3.6).move(-.4, 0, 0), eye, end) != null,
                "The same model translation changes the local selection box");
    }

    private static void independentTimelines() {
        int compared = 0, supported = 0, corrected = 0, pendingHits = 0;
        for (int a : new int[] {0, 20, 80, 150, 250})
            for (int b : new int[] {0, 20, 150, 250})
                for (double split : new double[] {.2, .5, .8})
                    for (int phase : new int[] {0, 17, 41}) {
                        Result result = run(new Config(a, b, 6, 0, split, 0, phase, 42));
                        for (Frame frame : result.frames()) {
                            if (frame.time < 350 || !frame.estimate.hasBounds()) continue;
                            compared++;
                            require(
                                    frame.actual + 1e-6 >= frame.estimate.minimumDistance()
                                            && frame.actual
                                                    <= frame.estimate.maximumDistance() + 1e-6,
                                    "Constant-motion truth escaped bounds at a="
                                            + a
                                            + ",b="
                                            + b
                                            + ",split="
                                            + split
                                            + ",phase="
                                            + phase
                                            + ",t="
                                            + frame.time
                                            + ", actual="
                                            + frame.actual
                                            + ", estimate="
                                            + frame.estimate);
                            if (frame.estimate.verdict() == Verdict.IN_RANGE) supported++;
                            if (frame.offset > .001) corrected++;
                        }
                    }
        for (double split : new double[] {.2, .5, .8}) {
            Result result = run(new Config(250, 20, 6, 2, split, 0, 0, 42));
            require(
                    result.damageTime() >= 0
                            && result.appliedTime() >= result.damageTime()
                            && result.reportedTime() >= result.appliedTime(),
                    "Knockback causality cannot run backwards");
            for (Frame f : result.frames()) if (f.pendingSelf && f.actual <= 3) pendingHits++;
        }
        for (int jitter : new int[] {10, 25})
            for (int seed = 0; seed < 12; seed++) {
                Result result = run(new Config(150, 80, 6, 2, .8, jitter, 17, seed));
                for (Frame f : result.frames())
                    require(
                            Double.isFinite(f.actual) && f.arrival >= f.time,
                            "Jitter cannot reorder source movement or produce an attack before it was sent");
                require(
                        result.appliedTime() >= result.damageTime()
                                && result.reportedTime() >= result.appliedTime(),
                        "Jitter preserves knockback causality");
                Result steady = run(new Config(150, 80, 6, 0, .8, jitter, 17, seed));
                for (Frame f : steady.frames())
                    if (f.time >= 350 && f.estimate.hasBounds()) {
                        require(
                                f.actual >= f.estimate.minimumDistance() - 1e-6
                                        && f.actual <= f.estimate.maximumDistance() + 1e-6,
                                "Jittered steady motion escaped bounds: jitter="
                                        + jitter
                                        + ",seed="
                                        + seed
                                        + ",t="
                                        + f.time
                                        + ",truth="
                                        + f.actual
                                        + ",model="
                                        + f.estimate);
                    }
            }
        require(
                compared > 1000 && supported > 0 && corrected > 0 && pendingHits > 0,
                "Need useful predictions and delayed-knockback hits, not a model that always abstains");
        System.out.println(
                "Independent timeline checks: "
                        + compared
                        + ", in-range="
                        + supported
                        + ", corrections="
                        + corrected
                        + ", pending-KB hits="
                        + pendingHits);
    }

    private static void near(double a, double b, String message) {
        require(Double.isFinite(b) && Math.abs(a - b) < 1e-6, message + ": " + a + " != " + b);
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
