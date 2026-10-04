package com.blanoir.moons.client.utils.rotation.aim;

import com.blanoir.moons.client.compat.math.VecMath;
import com.blanoir.moons.client.utils.rotation.Rotation;

import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * C: Scaffold support/cell candidate filtering and nearest-cell selection.
 * Preserves support enumeration, stable tie ordering, the candidate cap, continuity bias and
 * early exit. Caller supplies Keep-Y policy, previous placement and point evaluator. No
 * history is updated and no mode configuration, input or rotation ownership is read.
 */
public final class TargetSelectorC {
    private static final EnumFacing[] FACES = EnumFacing.values();

    private TargetSelectorC() {}

    public static List<BlockTarget> collect(
            Minecraft client,
            Vec3 playerPosition,
            BlockPos desired,
            int searchRadius,
            boolean keepHeight,
            int startY,
            Predicate<IBlockState> interactable) {
        if (!client.theWorld
                .getBlockState(desired)
                .getBlock()
                .isReplaceable(client.theWorld, desired)) return List.of();

        List<BlockTarget> targets = new ArrayList<>();
        Vec3 targetCenter = VecMath.atCenterOf(desired);
        double reachSqr = Math.pow(client.playerController.getBlockReachDistance(), 2.0D);
        for (int x = -searchRadius; x <= searchRadius; x++) {
            for (int y = -searchRadius; y <= 0; y++) {
                for (int z = -searchRadius; z <= searchRadius; z++) {
                    BlockPos support = desired.add(x, y, z);
                    IBlockState state = client.theWorld.getBlockState(support);
                    if (state.getBlock().isReplaceable(client.theWorld, support)
                            || interactable.test(state)
                            || playerPosition.squareDistanceTo(VecMath.atCenterOf(support))
                                    > reachSqr
                            || keepHeight && support.getY() >= startY) continue;
                    for (EnumFacing face : FACES) {
                        if (face == EnumFacing.DOWN) continue;
                        BlockPos placed = support.offset(face);
                        if (placed.getY() > desired.getY()
                                || !client.theWorld
                                        .getBlockState(placed)
                                        .getBlock()
                                        .isReplaceable(client.theWorld, placed)) continue;
                        // Each offset visits one distinct support, and each face occurs once.
                        targets.add(new BlockTarget(support, face));
                    }
                }
            }
        }
        targets.sort(
                Comparator.comparingDouble(
                                (BlockTarget target) -> target.placeDistanceSquared(targetCenter))
                        .thenComparingDouble(target -> target.supportDistanceSquared(targetCenter))
                        .thenComparingInt(target -> target.face() == EnumFacing.UP ? 0 : 1));
        return targets;
    }

    public static BlockAim select(
            BlockPos desired,
            Rotation base,
            List<BlockTarget> targets,
            int maxCandidates,
            BlockPos previousPlaced,
            Function<BlockTarget, BlockAim> aimAt) {
        Vec3 desiredCenter = VecMath.atCenterOf(desired);
        float baseYaw = base.yaw();
        float basePitch = base.pitch();
        BlockAim best = null;
        double bestScore = Double.MAX_VALUE;
        double bestPlaceDistance = Double.MAX_VALUE;
        int evaluated = 0;
        for (BlockTarget target : targets) {
            double placeDistance = target.placeDistanceSquared(desiredCenter);
            if (best != null && placeDistance > bestPlaceDistance) break;
            if (evaluated++ >= maxCandidates) break;
            BlockAim candidate = aimAt.apply(target);
            if (candidate == null) continue;
            double continuity =
                    previousPlaced != null && target.support().equals(previousPlaced)
                            ? -2.0D
                            : 0.0D;
            double rotationDistance = AimSolverE.distance(candidate.rotation(), baseYaw, basePitch);
            double score = rotationDistance + continuity;
            if (score < bestScore) {
                best = candidate;
                bestScore = score;
                bestPlaceDistance = placeDistance;
            }
            // An exact cell with a near-continuous angle cannot be improved by
            // distant chain candidates; avoid unnecessary ray scans.
            if (placeDistance == 0.0D && rotationDistance <= 2.0D) break;
        }
        return best;
    }
}
