package com.blanoir.tempos.client.manager.input.click;

import com.blanoir.tempos.client.manager.lease.RotationLease;
import com.blanoir.tempos.client.utils.math.RandomRange;

import net.minecraft.client.player.LocalPlayer;

/** Click-linked raw mouse counts, accumulated on ticks and consumed once per frame. */
public final class ClickJitter {
    private double yaw, pitch, steps, stepCount;
    private float pendingYaw, pendingPitch;

    public void clicked() {
        yaw = RandomRange.nextDouble(-7, 7);
        pitch = RandomRange.nextDouble(-7, 7);
        steps = stepCount = (Math.abs(yaw) + Math.abs(pitch)) * .45;
    }

    public void tick() {
        if (RotationLease.hasSilentRotation()) {
            reset();
            return;
        }
        if (steps > 0) {
            pendingYaw += (float) (yaw / stepCount);
            pendingPitch += (float) (pitch / stepCount);
            steps--;
        } else pendingYaw = pendingPitch = 0;
    }

    public void frame(LocalPlayer player, double sensitivity) {
        if (RotationLease.hasSilentRotation()) {
            reset();
            return;
        }
        if (pendingYaw == 0 && pendingPitch == 0) return;
        float factor = (float) sensitivity * .6f + .2f;
        float scale = factor * factor * factor * 8;
        // Entity.turn applies vanilla's 0.15 degree scale, pitch clamp and turn hooks.
        player.turn((int) pendingYaw * scale, (int) pendingPitch * scale);
        pendingYaw = pendingPitch = 0;
    }

    public void reset() {
        yaw = pitch = steps = stepCount = 0;
        pendingYaw = pendingPitch = 0;
    }
}
