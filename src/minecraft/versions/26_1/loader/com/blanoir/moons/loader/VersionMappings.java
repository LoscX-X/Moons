package com.blanoir.moons.loader;

import com.blanoir.moons.loader.common.mapping.CommonMappings;
import com.blanoir.moons.loader.common.mapping.MappingService;
import com.blanoir.moons.loader.sodium.mapping.SodiumMappings;

/** Composes baseline and explicit compatibility targets for Minecraft 26.1.2. */
public final class VersionMappings {
    private VersionMappings() {}

    public static String version() {
        return "26.1.2";
    }

    public static MappingService create() {
        MappingService common = CommonMappings.create();

        return SodiumMappings.apply(common);
    }
}
