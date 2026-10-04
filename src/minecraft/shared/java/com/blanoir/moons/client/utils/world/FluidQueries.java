package com.blanoir.moons.client.utils.world;

import net.minecraft.block.material.Material;

public final class FluidQueries {
    private FluidQueries() {}

    public static boolean isSource(LegacyWorld.FluidState state, Material material) {
        return state.is(material) && state.isSource();
    }
}
