package com.blanoir.moons.client.utils.rotation.quantize;

import com.blanoir.moons.client.utils.rotation.Rotation;

import net.minecraft.util.Mth;

/**
 * A: Mouse-angle quantization only. Pure methods retain sensitivity clamping, vanilla
 * float rounding, wrapped yaw and pitch bounds. Adapter injects the owner's yaw/pitch
 * quantizers and preserves yaw-before-pitch evaluation; it performs no host reads itself.
 */
public final class QuantizerA {
    private QuantizerA() {}

    @FunctionalInterface
    public interface Axis {
        float apply(float base, float desired);
    }

    public record Adapter(Axis yaw, Axis pitch) {
        public Rotation apply(Rotation base, Rotation desired) {
            return new Rotation(
                    yaw.apply(base.yaw(), desired.yaw()),
                    pitch.apply(base.pitch(), desired.pitch()));
        }

        public Rotation relative(Rotation base, Rotation desired) {
            return new Rotation(
                    yaw.apply(base.yaw(), base.yaw() + Mth.wrapDegrees(desired.yaw() - base.yaw())),
                    pitch.apply(base.pitch(), desired.pitch()));
        }
    }

    public static float quantizeMouseStep(float lastSent, float desired, double sensitivity) {
        double step = mouseSensitivityStep(Mth.clamp(sensitivity, 0.0D, 1.0D));
        return quantizeYawWithStep(lastSent, desired, step);
    }

    public static double mouseSensitivityStep(double sensitivity) {
        double factor = sensitivity * 0.6D + 0.2D;
        return (double) ((float) (factor * factor * factor * 8.0D) * 0.15F);
    }

    public static float quantizeYawWithStep(float base, float desired, double step) {
        if (!Double.isFinite(step) || step <= 1.0E-7D) return desired;
        return base + (float) (Math.round(Mth.wrapDegrees(desired - base) / step) * step);
    }

    public static float quantizePitchWithStep(float base, float desired, double step) {
        if (!Double.isFinite(step) || step <= 1.0E-7D) return Mth.clamp(desired, -90, 90);
        return Mth.clamp(base + (float) (Math.round((desired - base) / step) * step), -90, 90);
    }

    public static double mouseSensitivityGcd(double sensitivity) {
        return mouseSensitivityStep(Mth.clamp(sensitivity, 0.0D, 1.0D));
    }
}
