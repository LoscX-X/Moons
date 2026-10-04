package com.blanoir.moons.client.utils.rotation.aim;

import com.blanoir.moons.client.utils.rotation.Rotation;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.Vec3;

/**
 * A: AimAssist point-to-angle solution with the original camera error and point distance.
 * Stateless; reads the live eye/camera and performs no prediction, sampling or output writes.
 */
public final class AimSolverA {
    private AimSolverA() {}

    public record Result(
            EntityLivingBase entity,
            Vec3 aimPoint,
            Rotation rotation,
            double angle,
            double distanceSquared) {}

    public static Result solve(Minecraft client, EntityLivingBase entity, Vec3 aimPoint) {
        Vec3 eyePos = client.thePlayer.getPositionEyes(1F);

        Rotation rotation = AimSolverD.rotationTo(eyePos, aimPoint);

        return new Result(
                entity,
                aimPoint,
                rotation,
                AimSolverD.angleBetween(
                        client.thePlayer.rotationYaw, client.thePlayer.rotationPitch, rotation),
                eyePos.squareDistanceTo(aimPoint));
    }
}
