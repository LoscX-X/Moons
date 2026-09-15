package com.blanoir.moons.client.module.impl.combat.silentaura;

import com.blanoir.moons.client.utils.math.MathUtils;
import com.blanoir.moons.client.utils.rotation.Rotation;
import com.blanoir.moons.client.utils.rotation.aim.AimProfile;
import com.blanoir.moons.client.utils.rotation.aim.AimProfileA;
import com.blanoir.moons.client.utils.rotation.aim.AimProfileB;
import com.blanoir.moons.client.utils.rotation.quantize.QuantizerA;
import com.blanoir.moons.client.utils.rotation.smooth.SmoothA;
import com.google.gson.Gson;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Reproducible component experiment, not a Minecraft/visibility/attack simulator.
 * Calls the production frame integrator, packet controller and mouse quantizer.
 * Frame corrections, prediction, noise and point selection are deliberately isolated out.
 */
public final class SilentAuraRotationSimulation {
    private static final double DT = .05;
    private static final int TICKS = 80;
    private static final int SEEDS = 8;
    private static final String[] SCENARIOS = {
        "yaw90", "pitch60", "diagonal", "reverse", "moving", "wrap", "overlap"
    };

    record Settings(
            String stage,
            String mode,
            boolean optimize,
            double smooth,
            int angleStep,
            double smoothing,
            double sensitivity,
            int fps) {}

    record Sample(
            double t,
            double yaw,
            double pitch,
            double targetYaw,
            double targetPitch,
            double yawSpeed,
            double pitchSpeed,
            double yawAcceleration,
            double pitchAcceleration,
            double yawJerk,
            double pitchJerk,
            double error) {}

    record Metrics(
            double firstArrivalMs,
            double settleMs,
            double rmsError,
            double overshootYaw,
            double overshootPitch,
            double maxYawSpeed,
            double maxPitchSpeed,
            double maxYawAcceleration,
            double maxPitchAcceleration,
            double maxYawJerk,
            double maxPitchJerk,
            double p95YawAcceleration,
            double p95PitchAcceleration) {}

    record Trace(
            Settings settings, String scenario, long seed, Metrics metrics, List<Sample> samples) {}

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        Path output = Path.of(args.length == 0 ? "rotation-simulation" : args[0]);
        Files.createDirectories(output);
        verifyReplay();
        var traces = new ArrayList<Trace>();
        var csv =
                new StringBuilder(
                        "stage,mode,optimize,smooth,angle_step,smoothing,sensitivity,fps,scenario,seed,"
                                + "first_arrival_ms,settle_ms,rms_error_deg,overshoot_yaw_deg,overshoot_pitch_deg,"
                                + "max_yaw_speed_deg_s,max_pitch_speed_deg_s,max_yaw_acceleration_deg_s2,"
                                + "max_pitch_acceleration_deg_s2,max_yaw_jerk_deg_s3,max_pitch_jerk_deg_s3,"
                                + "p95_yaw_acceleration_deg_s2,p95_pitch_acceleration_deg_s2\n");
        int count = 0;
        for (Settings settings : settings()) {
            for (String scenario : SCENARIOS) {
                for (int seedIndex = 0; seedIndex < SEEDS; seedIndex++) {
                    long seed = 20260915L + seedIndex;
                    Trace trace = simulate(settings, scenario, seed);
                    appendCsv(csv, trace);
                    if (seedIndex == 0 && keepTrace(settings, scenario)) traces.add(trace);
                    count++;
                }
            }
        }
        Files.writeString(output.resolve("summary.csv"), csv, StandardCharsets.UTF_8);
        Files.writeString(
                output.resolve("traces.json"), new Gson().toJson(traces), StandardCharsets.UTF_8);
        System.out.printf(
                "Rotation simulation passed: %d runs, %d traces, %d settings, %d seeds.%n",
                count, traces.size(), settings().size(), SEEDS);
        System.out.println("Results: " + output.toAbsolutePath());
    }

    private static List<Settings> settings() {
        var result = new LinkedHashSet<Settings>();
        for (String mode : List.of("Lock", "Balance")) {
            for (boolean optimize : List.of(false, true)) {
                result.add(new Settings("packet", mode, optimize, .58, 90, 0, .5, 60));
                for (double smooth : new double[] {.05, .3, .58, .8, 1}) {
                    result.add(new Settings("frame+packet", mode, optimize, smooth, 90, 0, .5, 60));
                }
                for (int fps : new int[] {30, 60, 144, 240}) {
                    for (double sensitivity : new double[] {.1, .5, 1}) {
                        result.add(
                                new Settings(
                                        "frame+packet",
                                        mode,
                                        optimize,
                                        .58,
                                        90,
                                        0,
                                        sensitivity,
                                        fps));
                    }
                }
            }
        }
        for (int step : new int[] {30, 60, 90, 120, 180}) {
            for (double smoothing : new double[] {0, .25, .5, .75, 1}) {
                result.add(new Settings("packet", "FullLock", false, .58, step, smoothing, .5, 60));
            }
        }
        return List.copyOf(result);
    }

    private static boolean keepTrace(Settings s, String scenario) {
        if (s.fps != 60 || s.sensitivity != .5 || scenario.equals("overlap")) return false;
        if (s.mode.equals("FullLock")) {
            return scenario.equals("diagonal")
                    && s.angleStep == 90
                    && (s.smoothing == 0 || s.smoothing == .5 || s.smoothing == 1);
        }
        return s.stage.equals("frame+packet")
                && (s.smooth == .58
                        || (scenario.equals("diagonal") && (s.smooth == .05 || s.smooth == 1)));
    }

    private static Trace simulate(Settings s, String scenario, long seed) {
        var random = new Random(seed);
        var packets =
                new PacketRotationSmoother(
                        !s.mode.equals("Balance"),
                        s.mode.equals("FullLock"),
                        (min, max) -> random.nextDouble(min, Math.nextUp(max)));
        AimProfile profile = s.mode.equals("Balance") ? new AimProfileB() : new AimProfileA();
        double gcd = QuantizerA.mouseSensitivityGcd(s.sensitivity);
        float initialYaw = scenario.equals("wrap") ? 179 : 0;
        Rotation sent = new Rotation(initialYaw, 0);
        packets.rebase(sent.yaw(), sent.pitch());
        var frame = new SmoothA.Motion(sent.yaw(), sent.pitch(), 0, 0);
        var tracking =
                new SmoothA.Tracking(
                        profile.response(s.smooth),
                        profile.maxYawSpeed(),
                        profile.maxPitchSpeed(),
                        1,
                        1,
                        0,
                        .075F,
                        !profile.lock());
        var samples = new ArrayList<Sample>();
        Rotation initialTarget = target(scenario, 0);
        samples.add(
                new Sample(
                        0,
                        initialYaw,
                        0,
                        initialTarget.yaw(),
                        initialTarget.pitch(),
                        0,
                        0,
                        0,
                        0,
                        0,
                        0,
                        error(sent, initialTarget)));
        double lastFrameTime = 0;
        int frameIndex = 1;
        for (int tick = 1; tick <= TICKS; tick++) {
            double time = tick * DT;
            // Exact scheduled frame times; no FPS rounding to frames-per-tick.
            while (frameIndex / (double) s.fps <= time + 1e-10) {
                double frameTime = frameIndex++ / (double) s.fps;
                frame =
                        SmoothA.track(
                                frame,
                                target(scenario, frameTime),
                                frameTime - lastFrameTime,
                                tracking);
                lastFrameTime = frameTime;
            }
            Rotation target = target(scenario, time);
            Rotation desired =
                    s.stage.equals("packet") ? target : new Rotation(frame.yaw(), frame.pitch());
            Rotation raw =
                    packets.sample(
                            tick,
                            initialYaw,
                            0,
                            desired.yaw(),
                            desired.pitch(),
                            s.sensitivity,
                            scenario.equals("overlap") && tick >= 4,
                            s.optimize,
                            s.angleStep,
                            s.smoothing);
            Rotation next =
                    new Rotation(
                            QuantizerA.quantizeYawWithStep(sent.yaw(), raw.yaw(), gcd),
                            QuantizerA.quantizePitchWithStep(sent.pitch(), raw.pitch(), gcd));
            // The simulated successful send is the only point that advances angle history.
            packets.confirm(next.yaw(), next.pitch());
            Sample previous = samples.getLast();
            double vy = MathUtils.wrappedAngleDifference(sent.yaw(), next.yaw()) / DT;
            double vp = (next.pitch() - sent.pitch()) / DT;
            double ay = (vy - previous.yawSpeed) / DT;
            double ap = (vp - previous.pitchSpeed) / DT;
            Sample sample =
                    new Sample(
                            time,
                            next.yaw(),
                            next.pitch(),
                            target.yaw(),
                            target.pitch(),
                            vy,
                            vp,
                            ay,
                            ap,
                            (ay - previous.yawAcceleration) / DT,
                            (ap - previous.pitchAcceleration) / DT,
                            error(next, target));
            require(
                    Double.isFinite(sample.error) && Double.isFinite(ay) && Double.isFinite(ap),
                    "Finite output");
            require(next.pitch() >= -90 && next.pitch() <= 90, "Physical pitch bounds");
            samples.add(sample);
            sent = next;
        }
        return new Trace(s, scenario, seed, metrics(samples, scenario), List.copyOf(samples));
    }

    private static Rotation target(String scenario, double time) {
        return switch (scenario) {
            case "yaw90" -> new Rotation(90, 0);
            case "pitch60" -> new Rotation(0, 60);
            case "diagonal" -> new Rotation(90, 45);
            case "reverse" -> time < 1 ? new Rotation(90, 45) : new Rotation(-90, -45);
            case "moving" ->
                    new Rotation(
                            (float) (65 * Math.sin(time * 2.2)),
                            (float) (35 * Math.sin(time * 1.7)));
            case "wrap" -> new Rotation(-179, 0);
            case "overlap" -> new Rotation(160, 0);
            default -> throw new IllegalArgumentException(scenario);
        };
    }

    private static double error(Rotation actual, Rotation target) {
        return Math.hypot(
                MathUtils.wrappedAngleDifference(actual.yaw(), target.yaw()),
                target.pitch() - actual.pitch());
    }

    private static Metrics metrics(List<Sample> samples, String scenario) {
        double first = -1, settle = -1, square = 0, oy = 0, op = 0;
        double vy = 0, vp = 0, ay = 0, ap = 0, jy = 0, jp = 0;
        double[] absAy = new double[TICKS], absAp = new double[TICKS];
        boolean staticTarget = !scenario.equals("moving") && !scenario.equals("reverse");
        int lastOutside = 0;
        for (int i = 1; i < samples.size(); i++) {
            Sample p = samples.get(i);
            if (staticTarget && first < 0 && p.error <= 1) first = p.t * 1000;
            if (p.error > 1) lastOutside = i;
            square += p.error * p.error;
            vy = Math.max(vy, Math.abs(p.yawSpeed));
            vp = Math.max(vp, Math.abs(p.pitchSpeed));
            ay = Math.max(ay, Math.abs(p.yawAcceleration));
            ap = Math.max(ap, Math.abs(p.pitchAcceleration));
            jy = Math.max(jy, Math.abs(p.yawJerk));
            jp = Math.max(jp, Math.abs(p.pitchJerk));
            absAy[i - 1] = Math.abs(p.yawAcceleration);
            absAp[i - 1] = Math.abs(p.pitchAcceleration);
            if (staticTarget) {
                oy = Math.max(oy, p.yaw - (scenario.equals("wrap") ? 181 : p.targetYaw));
                op = Math.max(op, p.pitch - p.targetPitch);
            }
        }
        // At least 250 ms inside tolerance and no later exit before the 4 s horizon.
        if (staticTarget && lastOutside + 5 <= TICKS) settle = (lastOutside + 1) * DT * 1000;
        Arrays.sort(absAy);
        Arrays.sort(absAp);
        return new Metrics(
                first,
                settle,
                Math.sqrt(square / TICKS),
                staticTarget ? oy : -1,
                staticTarget ? op : -1,
                vy,
                vp,
                ay,
                ap,
                jy,
                jp,
                absAy[75],
                absAp[75]);
    }

    private static void appendCsv(StringBuilder csv, Trace trace) {
        Settings s = trace.settings;
        Metrics m = trace.metrics;
        csv.append(
                String.format(
                        Locale.ROOT,
                        "%s,%s,%s,%.2f,%d,%.2f,%.2f,%d,%s,%d,%.3f,%.3f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f%n",
                        s.stage,
                        s.mode,
                        s.optimize,
                        s.smooth,
                        s.angleStep,
                        s.smoothing,
                        s.sensitivity,
                        s.fps,
                        trace.scenario,
                        trace.seed,
                        m.firstArrivalMs,
                        m.settleMs,
                        m.rmsError,
                        m.overshootYaw,
                        m.overshootPitch,
                        m.maxYawSpeed,
                        m.maxPitchSpeed,
                        m.maxYawAcceleration,
                        m.maxPitchAcceleration,
                        m.maxYawJerk,
                        m.maxPitchJerk,
                        m.p95YawAcceleration,
                        m.p95PitchAcceleration));
    }

    private static void verifyReplay() {
        var s = new Settings("frame+packet", "Balance", true, .58, 90, 0, .5, 144);
        require(
                simulate(s, "reverse", 71).equals(simulate(s, "reverse", 71)),
                "Seeded replay must be identical");
        require(
                !simulate(s, "reverse", 71).equals(simulate(s, "reverse", 72)),
                "Envelope seed must affect the experiment");
        int[] draws = {0};
        var packets =
                new PacketRotationSmoother(
                        true,
                        false,
                        (min, max) -> {
                            draws[0]++;
                            return (min + max) * .5;
                        });
        Rotation first = packets.sample(1, 0, 0, 90, 45, .5, false, true);
        require(
                first.equals(packets.sample(1, 0, 0, -90, -45, .5, false, true)),
                "Same tick must reuse sample");
        require(draws[0] == 4, "Same tick must not resample");
        // A sampled but unsent turn must not become the next angular position/history.
        Rotation unsentNext = packets.sample(2, 0, 0, 90, 45, .5, false, true);
        require(first.equals(unsentNext), "Unconfirmed samples must not advance angles");
        var full =
                new PacketRotationSmoother(
                        true,
                        true,
                        (min, max) -> {
                            throw new AssertionError("FullLock RNG");
                        });
        require(
                full.sample(1, 0, 0, 90, 45, .5, false, false).equals(new Rotation(90, 45)),
                "Default FullLock response");
        Trace wrap = simulate(new Settings("packet", "Lock", true, .58, 90, 0, .5, 60), "wrap", 71);
        require(wrap.metrics.maxYawSpeed < 100, "Yaw wrap must take the short arc");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
