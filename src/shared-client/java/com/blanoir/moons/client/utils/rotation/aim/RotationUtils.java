package com.blanoir.moons.client.utils.rotation.aim;

import com.blanoir.moons.client.utils.rotation.Rotation;
import com.blanoir.moons.client.utils.math.MathUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;

/** Stateless Minecraft yaw/pitch and angular-distance calculations. */
public final class RotationUtils {
    private RotationUtils() {
    }

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
        if (client == null || client.options == null) {
            return desired;
        }
        return quantizeMouseStep(lastSent, desired, client.options.sensitivity().get());
    }

    /** Pure variant of {@link #quantizeMouseStep(float, float)} for callers that already hold the sensitivity. */
    public static float quantizeMouseStep(float lastSent, float desired, double sensitivity) {
        return HumanAimSimulator.quantizeMouseStep(lastSent, desired, sensitivity);
    }
}
