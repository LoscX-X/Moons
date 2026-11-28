package com.blanoir.tempos.client.module.impl.world.scaffold;

import com.blanoir.tempos.client.manager.placement.PlacementRayPolicy;
import com.blanoir.tempos.client.utils.rotation.Rotation;
import com.blanoir.tempos.client.utils.rotation.aim.BlockAim;
import com.blanoir.tempos.client.utils.rotation.aim.BlockTarget;
import com.blanoir.tempos.client.utils.rotation.quantize.MouseAngleQuantizer;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Predicate;

/**
 * GodBridge ground-row target selection. Enumerates inertia and requested foot cells,
 * then horizontal support faces, preferring compass rays before turn recovery. Coverage
 * history and interaction exclusions are supplied by the mode, never retained here.
 */
public final class GodBridgeTargetSelector {
    private GodBridgeTargetSelector() {}

    public static List<BlockPos> cells(AABB feet, Vec3 velocity, int row) {
        return cells(feet, velocity, feet, row);
    }

    public static List<BlockPos> cells(AABB feet, Vec3 velocity, AABB intended, int row) {
        double dx = Math.clamp(velocity.x, -.5, .5);
        double dz = Math.clamp(velocity.z, -.5, .5);
        AABB predicted = feet.move(dx, 0, dz);
        AABB sweep = feet.expandTowards(dx, 0, dz).minmax(intended);
        List<BlockPos> cells = new ArrayList<>();
        for (int x = Mth.floor(sweep.minX); x <= Mth.floor(Math.nextDown(sweep.maxX)); x++) {
            for (int z = Mth.floor(sweep.minZ); z <= Mth.floor(Math.nextDown(sweep.maxZ)); z++) {
                cells.add(new BlockPos(x, row, z));
            }
        }
        Vec3 center =
                new Vec3(
                        (predicted.minX + predicted.maxX) * .5,
                        row + .5,
                        (predicted.minZ + predicted.maxZ) * .5);
        // A turn changes input before inertia follows. Keep both footprints in the search,
        // prioritizing actual momentum so the connecting block is not skipped during a turn.
        cells.sort(
                Comparator.<BlockPos>comparingDouble(cell -> overlap(predicted, cell))
                        .reversed()
                        .thenComparing(
                                Comparator.<BlockPos>comparingDouble(
                                                cell -> overlap(intended, cell))
                                        .reversed())
                        .thenComparingDouble(cell -> Vec3.atCenterOf(cell).distanceToSqr(center)));
        return cells;
    }

    private static double overlap(AABB feet, BlockPos cell) {
        double x =
                Math.max(
                        0, Math.min(feet.maxX, cell.getX() + 1) - Math.max(feet.minX, cell.getX()));
        double z =
                Math.max(
                        0, Math.min(feet.maxZ, cell.getZ() + 1) - Math.max(feet.minZ, cell.getZ()));
        return x * z;
    }

    public static BlockAim select(
            PlacementRayPolicy rays,
            Minecraft client,
            Vec3 eye,
            List<BlockPos> candidates,
            Rotation preferred,
            double range,
            Predicate<BlockPos> covered,
            Predicate<BlockState> interactable,
            MouseAngleQuantizer.Adapter quantizer) {
        return select(
                candidates,
                covered,
                (cell, recovery) -> {
                    for (Direction face : Direction.Plane.HORIZONTAL) {
                        BlockPos support = cell.relative(face.getOpposite());
                        BlockState state = client.level.getBlockState(support);
                        if (state.canBeReplaced()
                                || interactable.test(state)
                                || state.getCollisionShape(client.level, support).isEmpty())
                            continue;
                        BlockTarget target = new BlockTarget(support, face);
                        BlockAim aim =
                                GodBridgeFacePoints.resolve(
                                        rays, client, target, eye, preferred, range, quantizer,
                                        recovery);
                        if (aim != null) return aim;
                    }
                    return null;
                });
    }

    /** Shared selection loop; the world adapter owns actual support faces and ray validation. */
    static BlockAim select(
            List<BlockPos> cells,
            Predicate<BlockPos> covered,
            BiFunction<BlockPos, Boolean, BlockAim> aimAtCell) {
        for (boolean recovery : new boolean[] {false, true}) {
            for (BlockPos cell : cells) {
                if (covered.test(cell)) continue;
                BlockAim aim = aimAtCell.apply(cell, recovery);
                if (aim != null) return aim;
            }
        }
        return null;
    }
}
