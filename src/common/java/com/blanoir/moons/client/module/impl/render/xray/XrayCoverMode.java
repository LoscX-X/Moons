package com.blanoir.moons.client.module.impl.render.xray;

import com.blanoir.moons.client.chat.ClientChat;
import com.blanoir.moons.client.config.MoonsConfig;
import com.blanoir.moons.client.config.settings.BooleanSetting;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;

public final class XrayCoverMode {
    private static final BooleanSetting ENABLED =
            new BooleanSetting.Builder()
                    .name("xray.cover.enabled")
                    .defaultValue(MoonsConfig.COVER_MODE_DEFAULT_ENABLED)
                    .build();

    private XrayCoverMode() {}

    public static boolean isEnabled() {
        return ENABLED.get();
    }

    public static void setEnabled(Minecraft client, boolean newEnabled) {
        if (ENABLED.get() == newEnabled) {
            ClientChat.send(client, "Cover mode is already " + statusText() + ".");
            return;
        }

        ENABLED.set(newEnabled);

        if (OreScanner.isClientWorldReady(client)) {
            OreCache.removeInvalidPositions(client);
            OreScanner.requestFullRescan(client);
        }

        ClientChat.send(client, "Cover mode " + statusText() + ". " + descriptionText());
    }

    public static String statusText() {
        return ENABLED.get() ? "enabled" : "disabled";
    }

    public static String descriptionText() {
        if (!ENABLED.get()) {
            return "All visible scanned diamond ores can be cached.";
        }

        return "Only diamond ores exposed to cave air are cached (direct air face plus at least "
                + MoonsConfig.COVER_MODE_MIN_AIR_BLOCKS
                + " air blocks within "
                + MoonsConfig.COVER_MODE_AIR_RADIUS_BLOCKS
                + " blocks).";
    }

    public static boolean shouldRecordDiamond(Minecraft client, BlockPos pos) {
        if (!ENABLED.get()) {
            return true;
        }

        if (!OreScanner.isClientWorldReady(client)) {
            return false;
        }

        BlockPos immutablePos = pos.immutable();

        if (!hasDirectAirFace(client, immutablePos)) {
            return false;
        }

        return countNearbyAirBlocks(client, immutablePos) >= MoonsConfig.COVER_MODE_MIN_AIR_BLOCKS;
    }

    private static boolean hasDirectAirFace(Minecraft client, BlockPos pos) {
        for (Direction direction : Direction.values()) {
            if (client.level.getBlockState(pos.relative(direction)).isAir()) {
                return true;
            }
        }

        return false;
    }

    private static int countNearbyAirBlocks(Minecraft client, BlockPos pos) {
        int airBlocks = 0;
        int radius = MoonsConfig.COVER_MODE_AIR_RADIUS_BLOCKS;
        BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();

        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    if (x == 0 && y == 0 && z == 0) {
                        continue;
                    }

                    mutablePos.set(pos.getX() + x, pos.getY() + y, pos.getZ() + z);
                    BlockState state = client.level.getBlockState(mutablePos);

                    if (state.isAir()) {
                        airBlocks++;
                    }
                }
            }
        }

        return airBlocks;
    }
}
