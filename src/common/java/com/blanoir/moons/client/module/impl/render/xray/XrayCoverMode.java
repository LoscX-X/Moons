package com.blanoir.moons.client.module.impl.render.xray;

import com.blanoir.moons.client.chat.ClientChat;
import com.blanoir.moons.client.config.MoonsConfig;
import com.blanoir.moons.client.config.settings.BooleanSetting;

import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;

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

        BlockPos immutablePos = new BlockPos(pos);

        if (!hasDirectAirFace(client, immutablePos)) {
            return false;
        }

        return countNearbyAirBlocks(client, immutablePos) >= MoonsConfig.COVER_MODE_MIN_AIR_BLOCKS;
    }

    private static boolean hasDirectAirFace(Minecraft client, BlockPos pos) {
        for (EnumFacing direction : EnumFacing.values()) {
            if (client.theWorld.isAirBlock(pos.offset(direction))) {
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
                    IBlockState state = client.theWorld.getBlockState(mutablePos);

                    if (state.getBlock() == net.minecraft.init.Blocks.air) {
                        airBlocks++;
                    }
                }
            }
        }

        return airBlocks;
    }
}
