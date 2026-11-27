package com.blanoir.tempos.loader.mapping;

import com.blanoir.tempos.loader.compat.sodium.SodiumMappings;
import com.blanoir.tempos.loader.compat.neoforge.NeoForgeMappings;

/** Composes baseline and explicit compatibility targets for Minecraft 26.1.2. */
public final class VersionMappings {
    private VersionMappings() {}

    public static String version() {
        return "26.1.2";
    }

    public static MappingService create() {
        MappingService common = CommonMappings.create();
        common = NeoForgeMappings.apply(common);
        return SodiumMappings.apply(common);
    }
}
