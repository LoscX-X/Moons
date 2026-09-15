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
 * D: GodBridge ground-row target selection. Enumerates swept foot cells, then horizontal
 * support faces in their original order and returns the first H point solution. Coverage
 * history and interaction exclusions are supplied by the mode, never retained here.
 */
public final class TargetSelectorD {
    private TargetSelectorD() {}

    public static List<BlockPos> cells(AABB feet, Vec3 velocity, int row) {
        AABB sweep =
                feet.expandTowards(
                        Mth.clamp(velocity.x, -.5, .5), 0, Mth.clamp(velocity.z, -.5, .5));
        List<BlockPos> cells = new ArrayList<>();
        for (int x = Mth.floor(sweep.minX); x <= Mth.floor(Math.nextDown(sweep.maxX)); x++) {
            for (int z = Mth.floor(sweep.minZ); z <= Mth.floor(Math.nextDown(sweep.maxZ)); z++) {
                cells.add(new BlockPos(x, row, z));
            }
        }
        Vec3 center =
                new Vec3((feet.minX + feet.maxX) * .5, row + .5, (feet.minZ + feet.maxZ) * .5);
        cells.sort(Comparator.comparingDouble(cell -> Vec3.atCenterOf(cell).distanceToSqr(center)));
        return cells;
    }

    public static BlockAim select(
            PlacementRaycast rays,
            Minecraft client,
            Vec3 eye,
            int row,
            Rotation preferred,
            double range,
            Predicate<BlockPos> covered,
            Predicate<BlockState> interactable,
            QuantizerA.Adapter quantizer) {
        for (BlockPos cell :
                cells(client.player.getBoundingBox(), client.player.getDeltaMovement(), row)) {
            if (covered.test(cell)) continue;
            for (Direction face : Direction.Plane.HORIZONTAL) {
                BlockPos support = cell.relative(face.getOpposite());
                BlockState state = client.level.getBlockState(support);
                if (state.canBeReplaced()
                        || interactable.test(state)
                        || state.getCollisionShape(client.level, support).isEmpty()) continue;
                BlockAim aim =
                        AimPointsH.resolve(
                                rays,
                                client,
                                new BlockTarget(support, face),
                                eye,
                                preferred,
                                range,
                                quantizer);
                if (aim != null) return aim;
            }
        }
        return null;
    }
}
