package com.blanoir.moons.client.utils.rotation.aim;

import com.blanoir.moons.client.utils.world.placement.FaceScanA;
import com.blanoir.moons.client.utils.world.placement.PlacementRaycast;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * F: AntiLava supporting-face target selection. Preserves the supplied face order, collision
 * checks and strict global best score across J face solutions. Source detection, event
 * debounce, interaction lifecycle and range configuration remain in the mode.
 */
public final class TargetSelectorF {
    private TargetSelectorF() {}

    public static BlockHitResult select(
            PlacementRaycast rays,
            Minecraft client,
            BlockPos source,
            Vec3 eye,
            double range,
            Direction[] supportFaces,
            double[] offsets) {
        BlockHitResult best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        for (Direction face : supportFaces) {
            BlockPos supportPos = source.relative(face.getOpposite());
            BlockState support = client.level.getBlockState(supportPos);
            if (support.getCollisionShape(client.level, supportPos).isEmpty()) {
                continue;
            }
            FaceScanA.Result candidate =
                    AimPointsJ.scan(
                            rays,
                            client,
                            new BlockTarget(supportPos, face),
                            eye,
                            range,
                            offsets,
                            bestScore);
            if (candidate != null) {
                best = candidate.sample().hit();
                bestScore = candidate.score();
            }
        }
        return best;
    }
}
