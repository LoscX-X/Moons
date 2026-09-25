package com.blanoir.moons.client.module.impl.combat.silentaura;

import com.blanoir.moons.client.utils.rotation.Rotation;
import com.blanoir.moons.client.utils.rotation.quantize.QuantizerA;

/** Bounded residual helper. The selected aim mode always owns the primary trajectory. */
final class LearnedPacketRotation {
    @FunctionalInterface
    interface Predictor {
        float[] predict(float[][] rows);
    }

    record Geometry(double eyeX, double eyeY, double eyeZ, double x, double y, double z) {
        double distance() {
            return Math.hypot(x - eyeX, z - eyeZ);
        }

        float yaw() {
            return (float) (Math.toDegrees(Math.atan2(z - eyeZ, x - eyeX)) - 90);
        }

        float pitch() {
            return (float) -Math.toDegrees(Math.atan2(y - eyeY, distance()));
        }

        boolean valid() {
            return Double.isFinite(eyeX)
                    && Double.isFinite(eyeY)
                    && Double.isFinite(eyeZ)
                    && Double.isFinite(x)
                    && Double.isFinite(y)
                    && Double.isFinite(z)
                    && distance() < 64
                    && Math.abs(y - eyeY) < 64;
        }

        boolean jumpedFrom(Geometry old) {
            return Math.hypot(x - old.x, z - old.z) > 6
                    || Math.abs(y - old.y) > 6
                    || Math.hypot(eyeX - old.eyeX, eyeZ - old.eyeZ) > 6
                    || Math.abs(eyeY - old.eyeY) > 6;
        }
    }

    private final Predictor predictor;
    private final float[][] rows = new float[LearnedAimModel.WINDOW][];
    private Object world;
    private Object target;
    private Geometry geometry;
    private Geometry pendingGeometry;
    private int count;
    private int next;
    private int sentTick = Integer.MIN_VALUE;
    private int sampleTick = Integer.MIN_VALUE;
    private long sentNanos;
    private boolean hasSent;
    private boolean hasStep;
    private float sentYaw;
    private float sentPitch;
    private float yawStep;
    private float pitchStep;
    private double sensitivity = Double.NaN;
    private double strength = Double.NaN;
    private Rotation expectedSent;
    private Rotation cached;
    private Rotation sampleBase;
    private String status = "idle";

    LearnedPacketRotation() {
        this(reusablePredictor());
    }

    private static Predictor reusablePredictor() {
        // Each mode owns its inference scratch, just as it owns its packet history.
        var workspace = new LearnedAimModel.Workspace();
        return rows -> {
            LearnedAimModel model = LearnedAimModel.bundled();
            return model == null ? null : model.predict(rows, workspace);
        };
    }

    LearnedPacketRotation(Predictor predictor) {
        this.predictor = predictor;
    }

    void observe(Object world, Object target, Geometry geometry) {
        if (world == null || target == null || geometry == null || !geometry.valid()) {
            reset();
            return;
        }
        if (this.world != world
                || this.target != target
                || (this.geometry != null && geometry.jumpedFrom(this.geometry))) reset();
        this.world = world;
        this.target = target;
        this.geometry = geometry;
    }

    Rotation adjust(
            int tick,
            long now,
            Rotation base,
            Rotation primary,
            double sensitivity,
            double strength,
            float previousYaw,
            float previousPitch,
            double yawMax,
            double pitchMax,
            double yawAcceleration,
            double pitchAcceleration) {
        if (geometry == null) return primary;
        if (!Double.isFinite(sensitivity) || !Double.isFinite(strength) || strength <= 0) {
            reset();
            return primary;
        }
        strength = Math.min(1, strength);
        if (Double.compare(this.sensitivity, sensitivity) != 0
                || Double.compare(this.strength, strength) != 0) {
            clearHistory();
            cached = null;
            this.sensitivity = sensitivity;
            this.strength = strength;
        }
        if (hasSent
                && (tick < sentTick
                        || tick > sentTick + 1
                        || now - sentNanos > 100_000_000L
                        || Math.abs(wrap(base.yaw() - sentYaw)) > .001F
                        || Math.abs(base.pitch() - sentPitch) > .001F)) {
            clearHistory();
            cached = null;
        }
        if (cached != null && sampleTick == tick) return cached;
        cached = primary;
        status = "warmup " + count + "/" + LearnedAimModel.WINDOW;
        if (count == LearnedAimModel.WINDOW) {
            float[][] window = new float[LearnedAimModel.WINDOW][];
            for (int i = 0; i < window.length; i++)
                window[i] = rows[(next + i) % window.length].clone();
            try {
                float[] output = predictor.predict(window);
                if (output == null
                        || output.length != 2
                        || !Float.isFinite(output[0])
                        || !Float.isFinite(output[1])) {
                    status = "bypass: model unavailable";
                } else {
                    float dy = wrap(primary.yaw() - base.yaw());
                    float dp = primary.pitch() - base.pitch();
                    float yaw =
                            assistAxis(
                                    dy,
                                    output[0],
                                    wrap(geometry.yaw() - base.yaw()),
                                    previousYaw,
                                    yawMax,
                                    yawAcceleration,
                                    strength);
                    // No target elevation input: use half the residual budget for Pitch.
                    float pitch =
                            assistAxis(
                                    dp,
                                    output[1],
                                    geometry.pitch() - base.pitch(),
                                    previousPitch,
                                    pitchMax,
                                    pitchAcceleration,
                                    strength * .5);
                    if (yaw != dy || pitch != dp) {
                        cached =
                                new Rotation(
                                        base.yaw() + yaw,
                                        Math.max(-90, Math.min(90, base.pitch() + pitch)));
                        status = "active";
                    } else {
                        status = "ready: primary retained";
                    }
                }
            } catch (RuntimeException failure) {
                status = "bypass: inference failed";
            }
        }
        double quantum = QuantizerA.mouseSensitivityGcd(sensitivity);
        expectedSent =
                new Rotation(
                        QuantizerA.quantizeYawWithStep(base.yaw(), cached.yaw(), quantum),
                        QuantizerA.quantizePitchWithStep(base.pitch(), cached.pitch(), quantum));
        sampleBase = base;
        sampleTick = tick;
        pendingGeometry = geometry;
        return cached;
    }

    /** Residual projection keeps direction, settling and the original mode's motion envelope. */
    static float assistAxis(
            float primary,
            float prediction,
            float error,
            float previous,
            double maxStep,
            double acceleration,
            double strength) {
        if (!Float.isFinite(prediction)
                || !Double.isFinite(strength)
                || strength <= 0
                || Math.abs(primary) < .001F
                || primary * prediction <= 0
                || primary * error <= 0
                || Math.abs(error) <= 1) return primary;
        double fade = Math.min(1, (Math.abs(error) - 1) / 4.0);
        double budget = Math.min(1.5, Math.abs(primary) * .25) * Math.min(1, strength) * fade;
        // Include legacy deadzone/overlap discontinuities without increasing them.
        double allowedAcceleration = Math.max(acceleration, Math.abs(primary - previous));
        double low = Math.max(primary - budget, Math.max(-maxStep, previous - allowedAcceleration));
        double high = Math.min(primary + budget, Math.min(maxStep, previous + allowedAcceleration));
        if (primary > 0) {
            low = Math.max(low, 0);
            high = Math.min(high, Math.max(primary, error));
        } else {
            high = Math.min(high, 0);
            low = Math.max(low, Math.min(primary, error));
        }
        if (low > primary || high < primary) return primary;
        double proposed =
                primary + Math.max(-budget, Math.min(budget, (prediction - primary) * strength));
        return (float) Math.max(low, Math.min(high, proposed));
    }

    /** Updates once per sent tick; render frames and candidate reads never create observations. */
    void confirm(int tick, long now, float yaw, float pitch) {
        if (pendingGeometry == null || sampleTick != tick || cached == null) return;
        if (!Float.isFinite(yaw)
                || !Float.isFinite(pitch)
                || Math.abs(wrap(yaw - expectedSent.yaw())) > .001F
                || Math.abs(pitch - expectedSent.pitch()) > .001F) {
            clearHistory();
            pendingGeometry = null;
            cached = null;
            return;
        }
        if (hasSent && tick == sentTick) return;
        double dt = (now - sentNanos) / 1_000_000.0;
        float dy = wrap(yaw - (hasSent ? sentYaw : sampleBase.yaw()));
        float dp = pitch - (hasSent ? sentPitch : sampleBase.pitch());
        boolean contiguous =
                hasSent
                        && tick == sentTick + 1
                        && dt >= 40
                        && dt <= 60
                        && Math.abs(dy) <= 90
                        && Math.abs(dp) <= 60;
        if (contiguous && hasStep) {
            rows[next] =
                    new float[] {
                        dy,
                        dp,
                        dy - yawStep,
                        dp - pitchStep,
                        wrap(pendingGeometry.yaw() - yaw),
                        (float) pendingGeometry.distance(),
                        (float) dt
                    };
            next = (next + 1) % rows.length;
            count = Math.min(count + 1, rows.length);
        } else if (!contiguous) {
            count = next = 0;
        }
        // Keep physical velocity even after timing invalidates the learning window.
        yawStep = dy;
        pitchStep = dp;
        hasStep = contiguous;
        hasSent = true;
        sentYaw = yaw;
        sentPitch = pitch;
        sentTick = tick;
        sentNanos = now;
    }

    static float wrap(float angle) {
        return angle - (float) Math.floor((angle + 180.0) / 360.0) * 360.0F;
    }

    String status() {
        return status;
    }

    int observations() {
        return count;
    }

    private void clearHistory() {
        count = next = 0;
        hasSent = hasStep = false;
        yawStep = pitchStep = 0;
        sentTick = Integer.MIN_VALUE;
    }

    void reset() {
        clearHistory();
        world = target = null;
        geometry = pendingGeometry = null;
        cached = null;
        sampleTick = Integer.MIN_VALUE;
        sensitivity = Double.NaN;
        strength = Double.NaN;
        expectedSent = null;
        status = "idle";
    }
}
