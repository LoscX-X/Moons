package com.blanoir.moons.client.management.rotation;

import com.blanoir.moons.client.utils.rotation.aim.AimSolverD;
import com.blanoir.moons.client.utils.rotation.quantize.QuantizerA;

import net.minecraft.client.Minecraft;

/** The shared mouse-step calculation; exact interaction/validated placement pairs bypass it. */
public final class RotationQuantizer {
    private RotationQuantizer() {}

    public static double mouseStep() {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.options == null) return 0;
        return QuantizerA.mouseSensitivityStep(client.options.sensitivity().get());
    }

    public static float yaw(float base, float desired) {
        return yaw(base, desired, mouseStep());
    }

    public static float pitch(float base, float desired) {
        return pitch(base, desired, mouseStep());
    }

    /** Changes only whole turns so vanilla can keep the last sent yaw's numeric domain. */
    public static float continuousYaw(float reference, float cameraYaw) {
        return AimSolverD.continuousYaw(reference, cameraYaw);
    }

    static float yaw(float base, float desired, double step) {
        return QuantizerA.quantizeYawWithStep(base, desired, step);
    }

    static float pitch(float base, float desired, double step) {
        return QuantizerA.quantizePitchWithStep(base, desired, step);
    }
}
