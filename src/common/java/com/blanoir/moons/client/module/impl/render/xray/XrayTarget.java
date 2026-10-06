package com.blanoir.moons.client.module.impl.render.xray;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;

public interface XrayTarget {
    String commandName();

    boolean isEnabled();

    int red();

    int green();

    int blue();

    boolean matches(Block block);

    default boolean matches(IBlockState state) {
        return matches(state.getBlock());
    }

    default boolean requiresCurrentState() {
        return false;
    }
}
