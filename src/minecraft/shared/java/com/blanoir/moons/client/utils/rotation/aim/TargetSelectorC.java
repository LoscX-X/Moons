package com.blanoir.moons.client.utils.rotation.aim;

import com.blanoir.moons.client.utils.rotation.Rotation;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

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
    private TargetSelectorC() {}

    public static List<BlockTarget> collect(
            Minecraft client,
            Vec3 playerPosition,
            BlockPos desired,
            int searchRadius,
            boolean keepHeight,
            int startY,
            Predicate<BlockState> interactable) {
        if (!client.level.getBlockState(desired).canBeReplaced()) return List.of();

        List<BlockTarget> targets = new ArrayList<>();
        Vec3 targetCenter = Vec3.atCenterOf(desired);
        double reachSqr = Math.pow(client.player.blockInteractionRange(), 2.0D);
        for (int x = -searchRadius; x <= searchRadius; x++) {
            for (int y = -searchRadius; y <= 0; y++) {
                for (int z = -searchRadius; z <= searchRadius; z++) {
                    BlockPos support = desired.offset(x, y, z);
                    BlockState state = client.level.getBlockState(support);
                    if (state.canBeReplaced()
                            || interactable.test(state)
                            || playerPosition.distanceToSqr(Vec3.atCenterOf(support)) > reachSqr
                            || keepHeight && support.getY() >= startY) continue;
                    for (Direction face : Direction.values()) {
                        if (face == Direction.DOWN) continue;
                        BlockPos placed = support.relative(face);
                        if (placed.getY() > desired.getY()
                                || !client.level.getBlockState(placed).canBeReplaced()) continue;
                        BlockTarget candidate = new BlockTarget(support, face);
                        if (!targets.contains(candidate)) targets.add(candidate);
                    }
                }
            }
        }
        targets.sort(
                Comparator.comparingDouble(
                                (BlockTarget target) ->
                                        Vec3.atCenterOf(target.placePos())
                                                .distanceToSqr(targetCenter))
                        .thenComparingDouble(
                                target ->
                                        Vec3.atCenterOf(target.support())
                                                .distanceToSqr(targetCenter))
                        .thenComparingInt(target -> target.face() == Direction.UP ? 0 : 1));
        return targets;
    }

    public static BlockAim select(
            BlockPos desired,
            Rotation base,
            List<BlockTarget> targets,
            int maxCandidates,
            BlockPos previousPlaced,
            Function<BlockTarget, BlockAim> aimAt) {
        Vec3 desiredCenter = Vec3.atCenterOf(desired);
        float baseYaw = base.yaw();
        float basePitch = base.pitch();
        BlockAim best = null;
        double bestScore = Double.MAX_VALUE;
        double bestPlaceDistance = Double.MAX_VALUE;
        int evaluated = 0;
        for (BlockTarget target : targets) {
            double placeDistance = Vec3.atCenterOf(target.placePos()).distanceToSqr(desiredCenter);
            if (best != null && placeDistance > bestPlaceDistance) break;
            if (evaluated++ >= maxCandidates) break;
            BlockAim candidate = aimAt.apply(target);
            if (candidate == null) continue;
            double continuity =
                    previousPlaced != null && target.support().equals(previousPlaced)
                            ? -2.0D
                            : 0.0D;
            double score =
                    AimSolverE.distance(candidate.rotation(), baseYaw, basePitch) + continuity;
            if (score < bestScore) {
                best = candidate;
                bestScore = score;
                bestPlaceDistance = placeDistance;
            }
            // An exact cell with a near-continuous angle cannot be improved by
            // distant chain candidates; avoid unnecessary ray scans.
            if (placeDistance == 0.0D
                    && AimSolverE.distance(candidate.rotation(), baseYaw, basePitch) <= 2.0D) break;
        }
        return best;
    }
}
