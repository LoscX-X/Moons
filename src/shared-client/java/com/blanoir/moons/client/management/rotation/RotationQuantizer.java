package com.blanoir.moons.client.management.rotation;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;

/** The shared mouse-step calculation; exact interaction/validated placement pairs bypass it. */
public final class RotationQuantizer {
    private RotationQuantizer() { }

    public static double mouseStep() {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.options == null) return 0;
        double factor = client.options.sensitivity().get() * 0.6D + 0.2D;
        return (double) ((float) (factor * factor * factor * 8.0D) * 0.15F);
    }

    public static float yaw(float base, float desired) { return yaw(base, desired, mouseStep()); }
    public static float pitch(float base, float desired) { return pitch(base, desired, mouseStep()); }

    static float yaw(float base, float desired, double step) {
        if (!Double.isFinite(step) || step <= 1.0E-7D) return desired;
        return base + (float) (Math.round(Mth.wrapDegrees(desired - base) / step) * step);
    }

    static float pitch(float base, float desired, double step) {
        if (!Double.isFinite(step) || step <= 1.0E-7D) return Mth.clamp(desired, -90, 90);
        return Mth.clamp(base + (float) (Math.round((desired - base) / step) * step), -90, 90);
    }
}
