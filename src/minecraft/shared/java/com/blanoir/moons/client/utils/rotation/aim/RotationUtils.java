package com.blanoir.moons.client.utils.rotation.aim;

import com.blanoir.moons.client.utils.math.MathUtils;
import com.blanoir.moons.client.utils.rotation.Rotation;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/** Stateless Minecraft yaw/pitch and angular-distance calculations. */
public final class RotationUtils {
    private RotationUtils() {}

    public static Rotation rotationTo(Vec3 from, Vec3 to) {
        return MathUtils.rotationTo(from, to);
    }

    public static double angleFromView(Minecraft client, Vec3 point) {
        LocalPlayer player = client == null ? null : client.player;
        if (player == null) return Double.POSITIVE_INFINITY;
        Rotation rotation = rotationTo(player.getEyePosition(), point);
        return angleBetween(player.getYRot(), player.getXRot(), rotation);
    }

    public static double angleBetween(float yaw, float pitch, Rotation target) {
        return MathUtils.angularDistance(yaw, pitch, target);
    }

    public static double viewAngle(Vec3 eye, Vec3 look, Vec3 point) {
        return MathUtils.viewAngle(eye, look, point);
    }

    /** Interpolates across the wrapped yaw boundary without changing response semantics. */
    public static float smoothRotation(float current, float target, double smoothing) {
        return MathUtils.smoothWrappedAngle(current, target, smoothing);
    }

    public static float quantizeMouseStep(float lastSent, float desired) {
        Minecraft client = Minecraft.getInstance();
        return quantizeMouseStep(lastSent, desired, client.options.sensitivity().get());
    }

    /** Pure variant of {@link #quantizeMouseStep(float, float)} for callers that already hold the sensitivity. */
    public static float quantizeMouseStep(float lastSent, float desired, double sensitivity) {
        double step = mouseSensitivityStep(Mth.clamp(sensitivity, 0.0D, 1.0D));
        return quantizeYawWithStep(lastSent, desired, step);
    }

    /**
     * Mouse quantum with vanilla's float rounding. This primitive does not clamp
     * sensitivity: host reads and bounded aim settings retain their own contracts.
     */
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

    /** Changes only whole turns, preserving the nearest continuous numeric yaw domain. */
    public static float continuousYaw(float reference, float cameraYaw) {
        if (!Float.isFinite(reference) || !Float.isFinite(cameraYaw)) return cameraYaw;
        double turns = Math.floor(((double) cameraYaw - reference + 180.0D) / 360.0D);
        return (float) (cameraYaw - turns * 360.0D);
    }
}
