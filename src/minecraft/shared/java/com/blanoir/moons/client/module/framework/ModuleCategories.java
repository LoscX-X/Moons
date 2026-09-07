package com.blanoir.moons.client.module.framework;

import java.util.List;

/** LiquidBounce-compatible top-level module taxonomy. */
public final class ModuleCategories {
    public static final String COMBAT = "Combat";
    public static final String PLAYER = "Player";
    public static final String MOVEMENT = "Movement";
    public static final String RENDER = "Render";
    public static final String WORLD = "World";
    public static final String MISC = "Misc";
    public static final String NETWORK = "Network";
    public static final String EXPERIMENT = "Experiment";

    private static final List<String> ORDERED =
            List.of(COMBAT, PLAYER, MOVEMENT, RENDER, WORLD, MISC, NETWORK, EXPERIMENT);

    private ModuleCategories() {}

    public static List<String> ordered() {
        return ORDERED;
    }
}
