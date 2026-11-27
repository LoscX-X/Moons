package com.blanoir.tempos.loader.mapping;

import com.blanoir.tempos.loader.compat.sodium.SodiumMappings;

/** Composes baseline and explicit compatibility targets for Minecraft 26.3. */
public final class VersionMappings {
    private VersionMappings() {}

    public static String version() {
        return "26.3";
    }

    public static MappingService create() {
        MappingService common = CommonMappings.create();

        return SodiumMappings.apply(common);
    }
}
