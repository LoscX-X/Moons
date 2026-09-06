package com.blanoir.moons.client.config;

public final class MoonsConfig {
    public static final String MOD_ID = "moons";
    public static final int SCAN_RADIUS_CHUNKS = 10;
    public static final int CHUNKS_PER_TICK = 2;
    public static final int CHAT_REPORT_INTERVAL_TICKS = 20;
    public static final int INVALID_CACHE_CLEAN_INTERVAL_TICKS = 40;
    public static final int DIAMOND_SCAN_MAX_Y_EXCLUSIVE = 128;
    public static final boolean COVER_MODE_DEFAULT_ENABLED = true;
    public static final int COVER_MODE_AIR_RADIUS_BLOCKS = 2;
    public static final int COVER_MODE_MIN_AIR_BLOCKS = 18;
    /** Static-scan cadence; crosshair probes remain one-shot per aimed block. */
    public static final int DESTROY_PACKET_INTERVAL_TICKS = 2;

    private MoonsConfig() {
    }
}
