package com.blanoir.moons.client.utils.rotation.aim;

import com.blanoir.moons.client.utils.world.placement.FaceGridScan;
import com.blanoir.moons.client.utils.world.placement.PlacementRaycast;

import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;

/**
 * F: AntiLava supporting-face target selection. Preserves the supplied face order, collision
 * checks and strict global best score across J face solutions. Source detection, event
 * debounce, interaction lifecycle and range configuration remain in the mode.
 */
public final class LavaSupportTargetSelector {
    private LavaSupportTargetSelector() {}

    public static MovingObjectPosition select(
            PlacementRaycast rays,
            Minecraft client,
            BlockPos source,
            Vec3 eye,
            double range,
            EnumFacing[] supportFaces,
            double[] offsets) {
        MovingObjectPosition best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        for (EnumFacing face : supportFaces) {
            BlockPos supportPos = source.offset(face.getOpposite());
            IBlockState support = client.theWorld.getBlockState(supportPos);
            if (support.getBlock().getCollisionBoundingBox(client.theWorld, supportPos, support)
                    == null) {
                continue;
            }
            FaceGridScan.Result candidate =
                    LavaSupportFacePoints.scan(
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
