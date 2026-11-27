package com.blanoir.tempos.client.module.impl.world.scaffold;

import com.blanoir.tempos.client.manager.placement.PlacementRayPolicy;
import com.blanoir.tempos.client.utils.rotation.Rotation;
import com.blanoir.tempos.client.utils.rotation.aim.BlockAim;
import com.blanoir.tempos.client.utils.rotation.aim.BlockTarget;

import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Fixed-direction block-face selection, currently the Vanilla Tower downward ray.
 * Performs one exact ray test without point-grid search or extra quantization. The caller
 * chooses the direction and target order; null means that ray does not hit this support/face.
 */
public final class FixedDirectionFacePoint {
    private FixedDirectionFacePoint() {}

    public static BlockAim resolve(
            PlacementRayPolicy rays,
            Minecraft client,
            BlockTarget target,
            Vec3 eye,
            Rotation direction,
            double range) {
        BlockHitResult hit =
                rays.traceFace(
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
