package com.blanoir.moons.client.module.impl.render.xray;

import net.minecraft.world.level.block.Block;

public interface XrayTarget {
    String commandName();

    boolean isEnabled();

    int red();

    int green();

    int blue();

    boolean matches(Block block);
}
