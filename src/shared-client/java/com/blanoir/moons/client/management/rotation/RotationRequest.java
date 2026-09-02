package com.blanoir.moons.client.management.rotation;

import com.blanoir.moons.client.utils.rotation.aim.RotationUtils;
import com.blanoir.moons.client.utils.rotation.Rotation;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;

/** Immutable requested server-facing rotation plus completion policy. */
public record RotationRequest(float yaw, float pitch, int smoothTicks, float tolerance, Runnable onConfirmed) {
    public RotationRequest {
        smoothTicks = Math.max(1, smoothTicks);
        tolerance = Math.max(0.01F, tolerance);
        onConfirmed = onConfirmed == null ? () -> { } : onConfirmed;
    }

    public static RotationRequest toward(Vec3 eye, Vec3 target, int smoothTicks, Runnable onConfirmed) {
        Objects.requireNonNull(eye, "eye");
        Objects.requireNonNull(target, "target");
        Rotation rotation = RotationUtils.rotationTo(eye, target);
        return new RotationRequest(rotation.yaw(), rotation.pitch(), smoothTicks, 0.35F, onConfirmed);
    }
}
