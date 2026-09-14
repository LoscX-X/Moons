package com.blanoir.moons.client.utils.rotation.aim;

import com.blanoir.moons.client.utils.rotation.Rotation;

import net.minecraft.client.Minecraft;
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

        return new Result(
                entity,
                aimPoint,
                rotation,
                AimSolverD.angleBetween(
                        client.player.getYRot(), client.player.getXRot(), rotation),
                eyePos.distanceToSqr(aimPoint));
    }
}
