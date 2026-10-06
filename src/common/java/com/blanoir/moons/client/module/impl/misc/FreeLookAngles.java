package com.blanoir.moons.client.module.impl.misc;

import net.minecraft.util.Mth;

/** Camera-only angles; mouse deltas have already passed through vanilla sensitivity/inversion. */
final class FreeLookAngles {
    private float yaw;
    private float pitch;

    void begin(float yaw, float pitch) {
        this.yaw = Mth.wrapDegrees(yaw);
        this.pitch = Mth.clamp(pitch, -90, 90);
    }

    void turn(double horizontal, double vertical) {
        if (!Double.isFinite(horizontal) || !Double.isFinite(vertical)) return;
        yaw = Mth.wrapDegrees(yaw + (float) (horizontal * .15));
        pitch = Mth.clamp(pitch + (float) (vertical * .15), -90, 90);
    }

    float yaw() {
        return yaw;
    }

    float pitch() {
        return pitch;
    }
}
