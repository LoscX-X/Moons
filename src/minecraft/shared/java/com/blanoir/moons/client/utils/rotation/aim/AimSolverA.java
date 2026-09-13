package com.blanoir.moons.client.utils.rotation.aim;

import com.blanoir.moons.client.utils.rotation.Rotation;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * A: AimAssist point-to-angle solution with the original camera error and point distance.
 * Stateless; reads the live eye/camera and performs no prediction, sampling or output writes.
 */
public final class AimSolverA {
    private AimSolverA() {}

    public record Result(
            LivingEntity entity,
            Vec3 aimPoint,
            Rotation rotation,
            double angle,
            double distanceSquared) {}

    public static Result solve(Minecraft client, LivingEntity entity, Vec3 aimPoint) {
        Vec3 eyePos = client.player.getEyePosition();

        Rotation rotation = AimSolverD.rotationTo(eyePos, aimPoint);

        float yawDifference = Math.abs(Mth.wrapDegrees(rotation.yaw() - client.player.getYRot()));

        float pitchDifference = Math.abs(rotation.pitch() - client.player.getXRot());

        return new Result(
                entity,
                aimPoint,
                rotation,
                Math.hypot(yawDifference, pitchDifference),
                eyePos.distanceToSqr(aimPoint));
    }
}
