package com.blanoir.moons.client.module.impl.render.xray;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

public interface XrayTarget {
    String commandName();

    boolean isEnabled();

    int red();

    int green();

    int blue();

    boolean matches(Block block);

    default boolean matches(BlockState state) {
        return matches(state.getBlock());
    }

    default boolean requiresCurrentState() {
        return false;
    }
}
