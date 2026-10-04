package com.blanoir.moons.client.utils.rotation.aim;

import com.blanoir.moons.client.compat.math.Mth;
import com.blanoir.moons.client.compat.math.VecMath;
import com.blanoir.moons.client.utils.rotation.Rotation;
import com.blanoir.moons.client.utils.rotation.quantize.QuantizerA;
import com.blanoir.moons.client.utils.world.placement.PlacementRaycast;

import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.Vec3;

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

    public static List<BlockPos> cells(AxisAlignedBB feet, Vec3 velocity, int row) {
        double dx = Mth.clamp(velocity.xCoord, -.5, .5);
        double dz = Mth.clamp(velocity.zCoord, -.5, .5);
        AxisAlignedBB predicted = VecMath.move(feet, dx, 0, dz);
        AxisAlignedBB sweep = feet.addCoord(dx, 0, dz);
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
        // At a diagonal corner, the closest empty cell can be beside/behind the player.
        // Prioritize support under the next footprint, then use its side cells as stepping stones.
        cells.sort(
                Comparator.<BlockPos>comparingDouble(cell -> overlap(predicted, cell))
                        .reversed()
                        .thenComparingDouble(
                                cell -> VecMath.atCenterOf(cell).squareDistanceTo(center)));
        return cells;
    }

    private static double overlap(AxisAlignedBB feet, BlockPos cell) {
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
            int row,
            Rotation preferred,
            double range,
            Predicate<BlockPos> covered,
            Predicate<IBlockState> interactable,
            QuantizerA.Adapter quantizer) {
        for (BlockPos cell :
                cells(
                        client.thePlayer.getEntityBoundingBox(),
                        VecMath.motion(client.thePlayer),
                        row)) {
            if (covered.test(cell)) continue;
            for (EnumFacing face : EnumFacing.Plane.HORIZONTAL) {
                BlockPos support = cell.offset(face.getOpposite());
                IBlockState state = client.theWorld.getBlockState(support);
                if (state.getBlock().isReplaceable(client.theWorld, support)
                        || interactable.test(state)
                        || state.getBlock().getCollisionBoundingBox(client.theWorld, support, state)
                                == null) continue;
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
