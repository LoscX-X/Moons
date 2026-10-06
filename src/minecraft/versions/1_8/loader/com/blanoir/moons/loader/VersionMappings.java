package com.blanoir.moons.loader;

import com.blanoir.moons.loader.common.mapping.CommonMappings;
import com.blanoir.moons.loader.common.mapping.MappingService;

/** Composes the verified vanilla/MCP baseline for Minecraft 1.8.9. */
public final class VersionMappings {
    private VersionMappings() {}

    public static String version() {
        return "1.8.9";
    }

    public static MappingService create() {
        return CommonMappings.create();
    }
}
