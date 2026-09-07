package com.blanoir.moons.client.utils;

import com.blanoir.moons.client.utils.player.HotbarQueries;
import com.blanoir.moons.client.utils.world.placement.BlockPlacementUtils;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/** Placement geometry and hotbar query contracts without a Minecraft world or registry bootstrap. */
public final class PlacementUtilsVerification {
    public static void main(String[] args) {
        verifyFaceCoordinates();
        verifyHitMatchingAndReach();
        verifyHotbarOrder();
        System.out.println("PLACEMENT_UTILS_VERIFIED");
    }

    private static void verifyFaceCoordinates() {
        BlockPos block = new BlockPos(2, -3, 5);
        Direction[] faces = {
            Direction.WEST, Direction.EAST, Direction.DOWN,
            Direction.UP, Direction.NORTH, Direction.SOUTH
        };
        Vec3[] expected = {
            new Vec3(2, -2.25D, 5.25D), new Vec3(3, -2.25D, 5.25D),
            new Vec3(2.75D, -3, 5.25D), new Vec3(2.75D, -2, 5.25D),
            new Vec3(2.75D, -2.75D, 5), new Vec3(2.75D, -2.75D, 6)
        };
        for (int i = 0; i < faces.length; i++) {
            require(
                    expected[i].equals(
                            BlockPlacementUtils.fullBlockFaceOffset(
                                    block, faces[i], 0.25D, -0.25D)),
                    "Centered face coordinates: " + faces[i]);
            require(
                    expected[i].equals(
                            BlockPlacementUtils.fullBlockFacePoint(block, faces[i], 0.75D, 0.25D)),
                    "Corner-relative face coordinates: " + faces[i]);
        }
        require(
                new Vec3(3.25D, -2, 4.75D)
                        .equals(
                                BlockPlacementUtils.fullBlockFaceOffset(
                                        block, Direction.UP, 0.75D, -0.75D)),
                "The caller owns sample clamping");
    }

    private static void verifyHitMatchingAndReach() {
        BlockPos support = new BlockPos(2, -3, 5);
        Vec3 point = new Vec3(2.5D, -2, 5.5D);
        BlockHitResult hit = new BlockHitResult(point, Direction.UP, support, true);
        require(BlockPlacementUtils.matchesBlock(hit, support), "The support block matches");
        require(
                BlockPlacementUtils.matchesFace(hit, support, Direction.UP),
                "The support face matches");
        require(
                !BlockPlacementUtils.matchesFace(hit, support, Direction.DOWN),
                "A different face fails");
        require(!BlockPlacementUtils.matchesBlock(hit, support.above()), "A different block fails");
        BlockHitResult planned = new BlockHitResult(Vec3.ZERO, Direction.UP, support, false);
        require(
                BlockPlacementUtils.matchesFace(hit, planned),
                "Face matching does not compare sample position or inside flags");
        BlockHitResult miss = BlockHitResult.miss(point, Direction.UP, support);
        require(
                !BlockPlacementUtils.matchesFace(miss, (BlockHitResult) null),
                "A miss must not inspect a missing plan");
        require(
                BlockPlacementUtils.withinReach(Vec3.ZERO, new Vec3(3, 4, 0), 5),
                "The exact reach boundary is included");
        require(
                !BlockPlacementUtils.withinReach(Vec3.ZERO, new Vec3(3, 4, 0), 4.99D),
                "Point distance must not become nearest-block-box distance");
        require(
                BlockPlacementUtils.withinReach(Vec3.ZERO, new Vec3(3, 4, 0), -5),
                "The primitive preserves squared-range arithmetic without adding a clamp");
    }

    private static void verifyHotbarOrder() {
        List<Integer> visited = new ArrayList<>();
        int slot =
                HotbarQueries.firstSlot(
                        index -> {
                            visited.add(index);
                            return index == 3 || index == 7;
                        });
        require(
                slot == 3 && visited.equals(List.of(0, 1, 2, 3)),
                "Search visits ascending slots and stops at the first match");
        visited.clear();
        int absent =
                HotbarQueries.firstSlot(
                        index -> {
                            visited.add(index);
                            return index == 9;
                        });
        require(
                absent == -1 && visited.equals(List.of(0, 1, 2, 3, 4, 5, 6, 7, 8)),
                "Search remains inside the nine hotbar slots and retains its not-found sentinel");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
