package com.blanoir.moons.client.module.impl.render.xray;

import com.blanoir.moons.client.config.MoonsConfig;
import net.minecraft.world.level.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public final class OreCache {
    private static final Map<BlockPos, XrayTarget> XRAY_POSITIONS =
            Collections.synchronizedMap(new HashMap<>());

    private OreCache() {
    }

    /**
     * 返回 true 表示这是新发现。
     * 返回 false 表示之前已经缓存过相同类型。
     */
    public static boolean add(BlockPos pos, XrayTarget target) {
        synchronized (XRAY_POSITIONS) {
            BlockPos immutablePos = pos.immutable();
            XrayTarget oldTarget = XRAY_POSITIONS.get(immutablePos);

            if (oldTarget == target) {
                return false;
            }

            XRAY_POSITIONS.put(immutablePos, target);
            return true;
        }
    }

    public static boolean add(BlockPos pos) {
        return add(pos, XrayBlockTarget.DIAMOND);
    }

    public static int size() {
        synchronized (XRAY_POSITIONS) {
            return XRAY_POSITIONS.size();
        }
    }

    public static void clear() {
        XRAY_POSITIONS.clear();
    }

    public static List<BlockPos> snapshot() {
        synchronized (XRAY_POSITIONS) {
            return new ArrayList<>(XRAY_POSITIONS.keySet());
        }
    }

    public static List<CachedXrayBlock> snapshotEntries() {
        synchronized (XRAY_POSITIONS) {
            List<CachedXrayBlock> entries = new ArrayList<>(XRAY_POSITIONS.size());

            XRAY_POSITIONS.forEach((pos, target) -> entries.add(new CachedXrayBlock(pos, target)));
            return entries;
        }
    }

    public static void removeFarPositions(Minecraft client) {
        if (!OreScanner.isClientWorldReady(client)) {
            return;
        }

        ChunkPos center = client.player.chunkPosition();
        int maxDistance = MoonsConfig.SCAN_RADIUS_CHUNKS + 2;

        synchronized (XRAY_POSITIONS) {
            Iterator<BlockPos> iterator = XRAY_POSITIONS.keySet().iterator();

            while (iterator.hasNext()) {
                BlockPos pos = iterator.next();

                int chunkX = pos.getX() >> 4;
                int chunkZ = pos.getZ() >> 4;

                int dx = Math.abs(chunkX - center.x());
                int dz = Math.abs(chunkZ - center.z());

                if (dx > maxDistance || dz > maxDistance) {
                    iterator.remove();
                }
            }
        }
    }

    public static void removeInvalidPositions(Minecraft client) {
        if (!OreScanner.isClientWorldReady(client)) {
            return;
        }

        synchronized (XRAY_POSITIONS) {
            XRAY_POSITIONS.entrySet().removeIf(entry -> {
                BlockPos pos = entry.getKey();
                XrayTarget target = entry.getValue();

                if (!target.isEnabled()) {
                    return true;
                }

                /*
                 * Keep a detected block rendered until the server/client state becomes air.
                 * Some servers temporarily mask ores as another non-air block; removing only
                 * on air avoids dropping a real target just because it is currently disguised.
                 */
                return client.level.getBlockState(pos).isAir();
            });
        }
    }

    public static void removePosition(BlockPos pos) {
        synchronized (XRAY_POSITIONS) {
            XRAY_POSITIONS.remove(pos);
        }
    }

    public static boolean isDiamondOre(Block block) {
        return XrayBlockTarget.DIAMOND.matches(block);
    }

    public record CachedXrayBlock(BlockPos pos, XrayTarget target) {
    }
}
