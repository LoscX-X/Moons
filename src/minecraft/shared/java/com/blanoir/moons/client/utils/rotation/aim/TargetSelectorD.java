package com.blanoir.moons.client.utils.rotation.aim;

import com.blanoir.moons.client.utils.rotation.Rotation;
import com.blanoir.moons.client.utils.rotation.quantize.QuantizerA;
import com.blanoir.moons.client.utils.world.placement.PlacementRaycast;

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
import java.util.function.Predicate;

/**
 * D: GodBridge ground-row target selection. Enumerates inertia and requested foot cells,
 * then horizontal support faces, preferring compass rays before turn recovery. Coverage
 * history and interaction exclusions are supplied by the mode, never retained here.
 */
public final class TargetSelectorD {
    private TargetSelectorD() {}

    public static List<BlockPos> cells(AABB feet, Vec3 velocity, int row) {
        return cells(feet, velocity, feet, row);
    }

    public static List<BlockPos> cells(AABB feet, Vec3 velocity, AABB intended, int row) {
        double dx = Mth.clamp(velocity.x, -.5, .5);
        double dz = Mth.clamp(velocity.z, -.5, .5);
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
            PlacementRaycast rays,
            Minecraft client,
            Vec3 eye,
            AABB intended,
            int row,
            Rotation preferred,
            double range,
            Predicate<BlockPos> covered,
            Predicate<BlockState> interactable,
            QuantizerA.Adapter quantizer) {
        List<BlockTarget> targets = new ArrayList<>();
        for (BlockPos cell :
                cells(
                        client.player.getBoundingBox(),
                        client.player.getDeltaMovement(),
                        intended,
                        row)) {
            if (covered.test(cell)) continue;
            for (Direction face : Direction.Plane.HORIZONTAL) {
                BlockPos support = cell.relative(face.getOpposite());
                BlockState state = client.level.getBlockState(support);
                if (state.canBeReplaced()
                        || interactable.test(state)
                        || state.getCollisionShape(client.level, support).isEmpty()) continue;
                BlockTarget target = new BlockTarget(support, face);
                targets.add(target);
                BlockAim aim =
                        AimPointsH.resolve(rays, client, target, eye, preferred, range, quantizer);
                if (aim != null) return aim;
            }
        }
        // Only relax the compass view after every normal candidate failed. Turning can
        // expose a valid side outside the narrow +/-12 degree window of the old heading.
        for (BlockTarget target : targets) {
            BlockAim aim =
                    AimPointsH.resolve(
                            rays, client, target, eye, preferred, range, quantizer, true);
            if (aim != null) return aim;
        }
        return null;
    }
}
