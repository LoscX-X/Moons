package com.blanoir.moons.client.utils.world;

import net.minecraft.tags.TagKey;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;

/** Fluid-source predicates with explicit tag or exact-type semantics. */
public final class FluidQueries {
    private FluidQueries() {}

    public static boolean isSource(FluidState state, TagKey<Fluid> tag) {
        return state.is(tag) && state.isSource();
    }

    public static boolean isSource(FluidState state, Fluid type) {
        return state.getType() == type && state.isSource();
    }
}
