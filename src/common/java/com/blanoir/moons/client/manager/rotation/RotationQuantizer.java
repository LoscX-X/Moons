package com.blanoir.moons.client.manager.rotation;

import com.blanoir.moons.client.utils.rotation.aim.AimAngles;
import com.blanoir.moons.client.utils.rotation.quantize.MouseAngleQuantizer;

import net.minecraft.client.Minecraft;

/** The shared mouse-step calculation; exact interaction/validated placement pairs bypass it. */
public final class RotationQuantizer {
    private RotationQuantizer() {}

    public static double mouseStep() {
        Minecraft client = Minecraft.getMinecraft();
        if (client == null || client.gameSettings == null) return 0;
        return MouseAngleQuantizer.mouseSensitivityStep(client.gameSettings.mouseSensitivity);
    }

    public static float yaw(float base, float desired) {
        return yaw(base, desired, mouseStep());
    }

    public static float pitch(float base, float desired) {
        return pitch(base, desired, mouseStep());
    }

    /** Changes only whole turns so vanilla can keep the last sent yaw's numeric domain. */
    public static float continuousYaw(float reference, float cameraYaw) {
        return AimAngles.continuousYaw(reference, cameraYaw);
    }

    static float yaw(float base, float desired, double step) {
        return MouseAngleQuantizer.quantizeYawWithStep(base, desired, step);
    }

    static float pitch(float base, float desired, double step) {
        return MouseAngleQuantizer.quantizePitchWithStep(base, desired, step);
    }
}
