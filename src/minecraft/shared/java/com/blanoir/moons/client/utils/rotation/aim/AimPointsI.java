package com.blanoir.moons.client.utils.rotation.aim;

import com.blanoir.moons.client.utils.rotation.Rotation;
import com.blanoir.moons.client.utils.world.placement.BlockPlacementUtils;

import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * I: Fixed-direction block-face selection, currently the Vanilla Tower downward ray.
 * Performs one exact ray test without point-grid search or extra quantization. The caller
 * chooses the direction and target order; null means that ray does not hit this support/face.
 */
public final class AimPointsI {
    private AimPointsI() {}

    public static BlockAim resolve(
            Minecraft client, BlockTarget target, Vec3 eye, Rotation direction, double range) {
        BlockHitResult hit =
                BlockPlacementUtils.traceFace(
                        client,
                        eye,
                        direction.yaw(),
                        direction.pitch(),
                        range,
                        target.support(),
                        target.face());
        return hit == null ? null : new BlockAim(target, direction, hit);
    }
}
