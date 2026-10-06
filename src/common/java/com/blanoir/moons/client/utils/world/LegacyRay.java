package com.blanoir.moons.client.utils.world;

import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.util.*;
import net.minecraft.world.IBlockAccess;

import java.util.List;

/** Explicit vanilla ray policy. Fluid source selection is handled by BlockLiquid.canCollideCheck. */
public record LegacyRay(Vec3 from, Vec3 to, Block block, Fluid fluid, Entity entity) {
    public enum Block {
        OUTLINE,
        COLLIDER
    }

    public enum Fluid {
        NONE,
        SOURCE_ONLY,
        ANY
    }

    public Vec3 getFrom() {
        return from;
    }

    public Vec3 getTo() {
        return to;
    }

    public LegacyWorld.Shape getBlockShape(IBlockState state, IBlockAccess world, BlockPos pos) {
        return state.getBlock().getMaterial().isLiquid()
                ? new LegacyWorld.Shape(List.of())
                : block == Block.COLLIDER
                        ? LegacyWorld.collision(state, world, pos, entity)
                        : LegacyWorld.outline(state, world, pos);
    }

    public LegacyWorld.Shape getFluidShape(
            LegacyWorld.FluidState state, IBlockAccess world, BlockPos pos) {
        return fluid != Fluid.NONE && !state.isEmpty() && (fluid == Fluid.ANY || state.isSource())
                ? new LegacyWorld.Shape(List.of(new AxisAlignedBB(0, 0, 0, 1, 1, 1)))
                : new LegacyWorld.Shape(List.of());
    }
}
