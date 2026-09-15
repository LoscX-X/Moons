package com.blanoir.moons.client.utils.world.placement;

import com.blanoir.moons.client.config.Settings;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

import java.util.HashMap;
import java.util.Map;

public final class PlacementRaycastVerification {
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        var world = new FixtureWorld();
        BlockPos wall = new BlockPos(1, 0, 0), support = new BlockPos(3, 0, 0);
        world.blocks.put(wall, Blocks.STONE.defaultBlockState());
        world.blocks.put(support, Blocks.STONE.defaultBlockState());
        Vec3 eye = new Vec3(0, .5, .5), end = new Vec3(4, .5, .5);
        var ray = context(eye, end, ClipContext.Fluid.NONE);
        require(
                PlacementRaycast.clipBlocks(world, ray, support, false).getBlockPos().equals(wall),
                "Normal ray stops at the first wall");
        BlockHitResult through = PlacementRaycast.clipBlocks(world, ray, support, true);
        require(
                BlockPlacementUtils.matchesFace(through, support, Direction.WEST),
                "ThroughBlocks selects the actual target face behind a wall");
        require(
                PlacementRaycast.clipBlocks(
                                        world,
                                        context(eye, new Vec3(2, .5, .5), ClipContext.Fluid.NONE),
                                        support,
                                        true)
                                .getType()
                        == HitResult.Type.MISS,
                "ThroughBlocks does not extend reach");
        world.blocks.put(support, Blocks.OAK_SLAB.defaultBlockState());
        require(
                PlacementRaycast.clipBlocks(
                                        world,
                                        context(
                                                new Vec3(0, .75, .5),
                                                new Vec3(4, .75, .5),
                                                ClipContext.Fluid.NONE),
                                        support,
                                        true)
                                .getType()
                        == HitResult.Type.MISS,
                "Partial shapes retain their geometry");
        world.blocks.put(support, Blocks.WATER.defaultBlockState());
        require(
                PlacementRaycast.clipBlocks(world, ray, support, true).getType()
                        == HitResult.Type.MISS,
                "Placement ray does not target fluid as a solid block");
        require(
                BlockPlacementUtils.matchesBlock(
                        PlacementRaycast.clipBlocks(
                                world,
                                context(eye, end, ClipContext.Fluid.SOURCE_ONLY),
                                support,
                                true),
                        support),
                "Pickup uses the source shape of the exact target");
        require(
                PlacementRaycast.blocksSegment(new AABB(1, 0, 0, 2, 1, 1), eye, end, 16),
                "Entity between eye and face blocks the ray");
        require(
                !PlacementRaycast.blocksSegment(new AABB(5, 0, 0, 6, 1, 1), eye, end, 16),
                "Entity behind the face does not block it");
        require(
                PlacementRaycast.blocksSegment(new AABB(-1, 0, 0, 1, 1, 1), eye, end, 16),
                "Eye inside another entity is an obstruction");

        for (String module :
                new String[] {
                    "autoweb",
                    "autolava",
                    "antiweb",
                    "antilava",
                    "autobed",
                    "blockin",
                    "scaffold",
                    "nofall",
                    "fastplace"
                }) {
            var policy = new PlacementRaycast(module);
            Settings.setBoolean(module + ".throughentity", false);
            Settings.setBoolean(module + ".throughblocks", false);
            require(
                    !policy.throughEntity() && !policy.throughBlocks(),
                    "Default independent flags");
            Settings.setBoolean(module + ".throughentity", true);
            require(
                    policy.throughEntity() && !policy.throughBlocks(),
                    "Entity option does not enable walls");
            Settings.setBoolean(module + ".throughentity", false);
            Settings.setBoolean(module + ".throughblocks", true);
            require(
                    !policy.throughEntity() && policy.throughBlocks(),
                    "Walls do not enable entity traversal");
            Settings.setBoolean(module + ".throughblocks", false);
        }
        require(
                !PlacementRaycast.hasItemRay() && PlacementRaycast.itemRay(through) == through,
                "Ordinary bucket use is unaffected outside an automated use");
        PlacementRaycast.withItemRay(
                through,
                () -> {
                    require(
                            PlacementRaycast.itemRay(null) == through,
                            "Matching use sees its target");
                    PlacementRaycast.withItemRay(
                            null,
                            () ->
                                    require(
                                            !PlacementRaycast.hasItemRay(),
                                            "Nested ordinary use stays ordinary"));
                    require(
                            PlacementRaycast.itemRay(null) == through,
                            "Nested use restores the outer ray");
                });
        try {
            PlacementRaycast.withItemRay(
                    through,
                    () -> {
                        throw new IllegalStateException("fixture");
                    });
        } catch (IllegalStateException expected) {
        }
        require(!PlacementRaycast.hasItemRay(), "Exceptional use clears its ray");
        System.out.println("MOONS_PLACEMENT_RAYS_VERIFIED");
    }

    private static ClipContext context(Vec3 eye, Vec3 end, ClipContext.Fluid fluid) {
        return new ClipContext(
                eye, end, ClipContext.Block.OUTLINE, fluid, CollisionContext.empty());
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static final class FixtureWorld implements BlockGetter {
        private final Map<BlockPos, BlockState> blocks = new HashMap<>();

        public BlockState getBlockState(BlockPos pos) {
            return blocks.getOrDefault(pos, Blocks.AIR.defaultBlockState());
        }

        public FluidState getFluidState(BlockPos pos) {
            return getBlockState(pos).getFluidState();
        }

        public BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }

        public int getHeight() {
            return 256;
        }

        public int getMinY() {
            return 0;
        }
    }
}
