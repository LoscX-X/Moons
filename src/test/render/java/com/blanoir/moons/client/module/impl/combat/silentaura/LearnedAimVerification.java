package com.blanoir.moons.client.module.impl.combat.silentaura;

import com.blanoir.moons.client.utils.rotation.Rotation;
import com.blanoir.moons.client.utils.rotation.quantize.QuantizerA;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.Random;

/** Real PyTorch parity plus temporal and angular-bound regression checks. */
public final class LearnedAimVerification {
    private static final Object WORLD = new Object();
    private static final Object TARGET = new Object();

    public static void main(String[] args) throws Exception {
        parity();
        historyAndBypass();
        residualBounds();
        primaryModes();
        discontinuities();
        settings();
        System.out.println(
                "Learned assist: parity, exact bypass, bounded residual, primary modes and resets passed");
    }

    private static void parity() throws Exception {
        LearnedAimModel model = LearnedAimModel.bundled();
        check(model != null, "Bundled model missing");
        check(
                model.checkpointHash()
                        .equals("e8da7f0e9c30515405f360532f727c29dffc0396503469dcfa4d1b6a9ac04691"),
                "Wrong checkpoint");
        try (DataInputStream in =
                new DataInputStream(
                        LearnedAimVerification.class.getResourceAsStream(
                                "/aim-gru128-parity.bin"))) {
            check(in.readInt() == 0x41494D54 && in.readInt() == 1, "Fixture format");
            int cases = in.readInt();
            check(cases == 1026 && in.readInt() == 16, "Fixture shape");
            float[][][] inputs = new float[cases][16][7];
            for (float[][] window : inputs)
                for (float[] row : window)
                    for (int i = 0; i < row.length; i++) row[i] = in.readFloat();
            double maxError = 0;
            var workspace = new LearnedAimModel.Workspace();
            long start = System.nanoTime();
            for (float[][] input : inputs) {
                float[] actual = model.predict(input);
                float[] reused = model.predict(input, workspace);
                for (int i = 0; i < 2; i++) {
                    check(
                            Float.floatToRawIntBits(actual[i])
                                    == Float.floatToRawIntBits(reused[i]),
                            "Reused inference scratch changed prediction bits");
                    float expected = in.readFloat();
                    double error = Math.abs(actual[i] - expected);
                    maxError = Math.max(maxError, error);
                    check(error < .0003, "PyTorch parity error " + error);
                }
            }
            check(in.read() == -1, "Trailing fixture data");
            System.out.printf(
                    "PyTorch cases=%d max absolute error=%.9f deg, two predictions/case=%.3f ms%n",
                    cases, maxError, (System.nanoTime() - start) / 1e6 / cases);
            float[] retained = model.predict(inputs[0], workspace);
            float[] saved = retained.clone();
            float[][] invalid = inputs[1].clone();
            invalid[8] = new float[7];
            invalid[8][0] = Float.NaN;
            try {
                model.predict(invalid, workspace);
                throw new AssertionError("Nonfinite input accepted");
            } catch (IllegalArgumentException expected) {
            }
            check(java.util.Arrays.equals(retained, saved), "Output was reused as scratch");
            check(
                    java.util.Arrays.equals(retained, model.predict(inputs[0], workspace)),
                    "Failed prediction contaminated next window");
        }
        byte[] bytes;
        try (var in =
                LearnedAimModel.class.getResourceAsStream(
                        "/assets/moons/models/aim-gru128-epoch7.bin")) {
            bytes = in.readAllBytes();
        }
        bytes[bytes.length - 1] ^= 1;
        try {
            LearnedAimModel.read(new ByteArrayInputStream(bytes));
            throw new AssertionError("Corruption accepted");
        } catch (IOException expected) {
        }
        try {
            LearnedAimModel.read(new ByteArrayInputStream(new byte[20]));
            throw new AssertionError("Truncation accepted");
        } catch (IOException expected) {
        }
        try {
            model.predict(new float[15][7]);
            throw new AssertionError("Bad shape accepted");
        } catch (IllegalArgumentException expected) {
        }
    }

    private static LearnedPacketRotation.Geometry geometry(float yaw) {
        double rad = Math.toRadians(yaw + 90);
        return new LearnedPacketRotation.Geometry(
                0, 0, 0, 3 * Math.cos(rad), -1, 3 * Math.sin(rad));
    }

    private static Rotation quantize(Rotation base, Rotation candidate) {
        double q = QuantizerA.mouseSensitivityStep(.5);
        return new Rotation(
                QuantizerA.quantizeYawWithStep(base.yaw(), candidate.yaw(), q),
                QuantizerA.quantizePitchWithStep(base.pitch(), candidate.pitch(), q));
    }

    private static Rotation adjust(
            LearnedPacketRotation helper,
            int tick,
            Rotation base,
            Rotation primary,
            double strength) {
        return helper.adjust(
                tick, tick * 50_000_000L, base, primary, .5, strength, 5, 0, 48, 32, 6, 4);
    }

    private static void historyAndBypass() {
        for (int scenario = 0; scenario < 5; scenario++) {
            final int kind = scenario;
            int[] calls = {0};
            LearnedPacketRotation helper =
                    new LearnedPacketRotation(
                            rows -> {
                                calls[0]++;
                                for (float[] row : rows)
                                    near(row[6], 50, .0001, "Observed interval");
                                if (kind == 2) return new float[] {Float.NaN, 0};
                                if (kind == 3) throw new IllegalStateException("fixture");
                                return new float[] {kind == 4 ? -50 : 50, 0};
                            });
            Rotation base = new Rotation(0, 0);
            for (int tick = 0; tick < 40; tick++) {
                helper.observe(WORLD, TARGET, geometry(base.yaw() + 60));
                Rotation primary = new Rotation(base.yaw() + 5, base.pitch());
                double strength = kind == 1 ? 0 : .35;
                Rotation result = adjust(helper, tick, base, primary, strength);
                if (kind != 0 || tick < 18)
                    check(result == primary, "Bypass changed primary " + kind);
                else check(result.yaw() > primary.yaw(), "Aligned model did not assist");
                if (strength > 0) {
                    int count = helper.observations();
                    check(
                            adjust(helper, tick, base, primary, strength) == result,
                            "Not tick cached");
                    check(count == helper.observations(), "Candidate inserted observation");
                }
                Rotation sent = quantize(base, result);
                helper.confirm(tick, tick * 50_000_000L, sent.yaw(), sent.pitch());
                int count = helper.observations();
                helper.confirm(tick, tick * 50_000_000L + 1, sent.yaw(), sent.pitch());
                check(count == helper.observations(), "Duplicate observation");
                base = sent;
            }
            check(calls[0] == (kind == 1 ? 0 : 22), "Warmup or call count");
        }
    }

    private static void residualBounds() {
        Random random = new Random(20260915);
        for (int i = 0; i < 20000; i++) {
            float primary = random.nextFloat() * 96 - 48;
            float previous = random.nextFloat() * 96 - 48;
            float error = random.nextFloat() * 360 - 180;
            float prediction = random.nextFloat() * 600 - 300;
            double strength = random.nextDouble();
            float result =
                    LearnedPacketRotation.assistAxis(
                            primary, prediction, error, previous, 48, 6, strength);
            double budget = Math.min(1.5, Math.abs(primary) * .25) * strength;
            check(Math.abs(result - primary) <= budget + .00001, "Residual budget");
            check(Math.abs(result) <= 48.00001, "Mode speed envelope");
            check(
                    Math.abs(result - previous)
                            <= Math.max(6, Math.abs(primary - previous)) + .00001,
                    "Worsened acceleration discontinuity");
            check(result * primary >= 0, "Direction changed");
            if (Math.abs(primary) <= Math.abs(error))
                check(Math.abs(result) <= Math.abs(error) + .00001, "New overshoot");
            if (primary * prediction <= 0 || primary * error <= 0 || Math.abs(error) <= 1)
                check(result == primary, "Gate changed original result");
        }
        near(
                LearnedPacketRotation.assistAxis(0, 100, 20, 0, 48, 6, 1),
                0,
                0,
                "No injected idle drift");
    }

    private static void primaryModes() {
        for (int mode = 0; mode < 3; mode++) {
            PacketRotationSmoother original =
                    new PacketRotationSmoother(mode != 1, mode == 2, (a, b) -> (a + b) / 2);
            PacketRotationSmoother assisted =
                    new PacketRotationSmoother(mode != 1, mode == 2, (a, b) -> (a + b) / 2);
            LearnedPacketRotation helper =
                    new LearnedPacketRotation(
                            rows -> {
                                throw new IllegalStateException("unavailable");
                            });
            Rotation base = new Rotation(0, 0);
            for (int tick = 0; tick < 70; tick++) {
                helper.observe(WORLD, TARGET, geometry(base.yaw() + 60));
                final int t = tick;
                Rotation expected =
                        original.sample(tick, 0, 0, base.yaw() + 20, 10, .5, false, true, 90, 0);
                Rotation actual =
                        assisted.sample(
                                tick,
                                0,
                                0,
                                base.yaw() + 20,
                                10,
                                .5,
                                false,
                                true,
                                90,
                                0,
                                (start, primary, py, pp, ym, pm, ya, pa) ->
                                        helper.adjust(
                                                t,
                                                t * 50_000_000L,
                                                start,
                                                primary,
                                                .5,
                                                .35,
                                                py,
                                                pp,
                                                ym,
                                                pm,
                                                ya,
                                                pa));
                check(expected.equals(actual), "Original mode bypass changed: " + mode);
                base = quantize(base, actual);
                original.confirm(base.yaw(), base.pitch());
                assisted.confirm(base.yaw(), base.pitch());
                helper.confirm(tick, tick * 50_000_000L, base.yaw(), base.pitch());
            }
            check(helper.status().startsWith("bypass"), "Mode never reached inference " + mode);
        }
    }

    private static void discontinuities() {
        for (int kind = 0; kind < 6; kind++) {
            LearnedPacketRotation helper = new LearnedPacketRotation(rows -> new float[] {5, 0});
            Rotation base = new Rotation(0, 0);
            for (int tick = 0; tick < 20; tick++) {
                helper.observe(WORLD, TARGET, geometry(base.yaw() + 60));
                Rotation result = adjust(helper, tick, base, new Rotation(base.yaw() + 5, 0), .35);
                base = quantize(base, result);
                helper.confirm(tick, tick * 50_000_000L, base.yaw(), base.pitch());
            }
            check(helper.observations() == 16, "Fixture history missing");
            Rotation primary = new Rotation(base.yaw() + 5, 0);
            switch (kind) {
                case 0 -> helper.observe(WORLD, new Object(), geometry(0));
                case 1 -> helper.observe(new Object(), TARGET, geometry(0));
                case 2 -> adjust(helper, 22, base, primary, .35);
                case 3 -> adjust(helper, 0, base, primary, .35);
                case 4 -> adjust(helper, 20, new Rotation(-90, 0), primary, .35);
                case 5 -> adjust(helper, 20, base, primary, .7);
                default -> throw new AssertionError();
            }
            check(helper.observations() == 0, "Reset missing " + kind);
        }
    }

    private static void settings() {
        String previous = SilentAuraConfig.aimMode();
        try {
            check(
                    !SilentAuraConfig.aimModeOptions().contains("learned_128"),
                    "Standalone mode still offered");
            for (String mode : new String[] {"lock", "balance", "full_lock"}) {
                check(SilentAuraConfig.aimMode(mode), "Missing original mode");
                for (var setting : SilentAuraConfig.settings()) {
                    if (setting.id().equals("learned_assist"))
                        check(setting.visibleWhen().getAsBoolean(), "Assist hidden");
                    if (setting.id().equals("smooth"))
                        check(
                                setting.visibleWhen().getAsBoolean() == !mode.equals("full_lock"),
                                "Original settings visibility");
                }
            }
        } finally {
            SilentAuraConfig.aimMode(previous);
        }
    }

    private static void near(double actual, double expected, double tolerance, String message) {
        check(Math.abs(actual - expected) <= tolerance, message);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
