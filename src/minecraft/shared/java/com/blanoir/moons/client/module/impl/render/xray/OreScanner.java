package com.blanoir.moons.client.module.impl.render.xray;

import com.blanoir.moons.client.chat.ClientChat;
import com.blanoir.moons.client.config.ClientBranding;
import com.blanoir.moons.client.config.MoonsConfig;
import com.blanoir.moons.client.config.settings.BooleanSetting;
import com.blanoir.moons.client.event.EventBus;
import com.blanoir.moons.client.module.framework.ModuleKeybinds;
import com.blanoir.moons.client.utils.world.BlockDistance;
import com.blanoir.moons.client.utils.world.ChunkKey;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class OreScanner {
    private static final Queue<ChunkPos> SCAN_QUEUE = new ArrayDeque<>();
    private static final ExecutorService SCAN_EXECUTOR =
            Executors.newSingleThreadExecutor(
                    runnable -> {
                        Thread thread =
                                new Thread(runnable, ClientBranding.name() + "-ore-scanner");
                        thread.setDaemon(true);
                        return thread;
                    });
    private static final AtomicInteger IN_FLIGHT = new AtomicInteger();
    private static final AtomicInteger SCAN_GENERATION = new AtomicInteger();
    private static final Queue<ScanBatch> COMPLETED_SCANS = new ConcurrentLinkedQueue<>();
    private static ScanBatch currentScan;
    private static int currentScanIndex;
    private static final AtomicInteger FOUND_TARGETS = new AtomicInteger();
    private static final int MAX_QUEUED_UPDATES = 8192;
    private static final int MAX_UPDATES_PER_TICK = 64;
    private static final Set<BlockPos> PENDING_UPDATES = new HashSet<>();

    /**
     * 防止同一个 chunk 在队列里重复出现。
     */
    private static final Set<Long> QUEUED_CHUNKS = new HashSet<>();

    /**
     * 已经扫过的 chunk。
     * 非强制重扫时不会重复加入。
     */
    private static final Set<Long> SCANNED_CHUNKS = new HashSet<>();

    private static final BooleanSetting AUTO_SCAN =
            new BooleanSetting.Builder().name("xray.enabled").defaultValue(false).build();

    private static int tickCounter = 0;
    private static ChunkPos lastCenterChunk = null;

    private OreScanner() {}

    public static void init() {
        ModuleKeybinds.registerAction("clearscan", () -> clearAll(Minecraft.getInstance()));
        EventBus.CLIENT_CONTEXT_CHANGED.register(
                "OreScanner.context",
                event -> {
                    resetScannerState();
                    OreCache.clear();
                    PluginXrayTargets.updateContext(event.client());
                });

        EventBus.TICK_END.register(
                "OreScanner.tickEnd",
                event -> {
                    Minecraft client = event.client();
                    PluginXrayTargets.tick(client);
                    if (AUTO_SCAN.get()) {
                        tickAutoScan(client);
                    } else {
                        COMPLETED_SCANS.clear();
                    }
                });
    }

    private static void toggleAutoScan(Minecraft client) {
        AUTO_SCAN.set(!AUTO_SCAN.get());
        XrayDestroyPacketMode.resetScan();

        if (AUTO_SCAN.get()) {
            resetScannerState();
            clearPendingUpdates();
            FOUND_TARGETS.set(0);
            enqueueNearbyChunks(client, true);
            ClientChat.send(client, "Xray enabled. Queue=" + SCAN_QUEUE.size());
        } else {
            resetScannerState();
            ClientChat.send(client, "Xray disabled. Cached=" + OreCache.size());
        }
    }

    private static void tickAutoScan(Minecraft client) {
        if (!isClientWorldReady(client)) {
            resetScannerState();
            return;
        }

        tickCounter++;

        ChunkPos currentCenter = client.player.chunkPosition();

        boolean movedToAnotherChunk =
                lastCenterChunk == null
                        || lastCenterChunk.x() != currentCenter.x()
                        || lastCenterChunk.z() != currentCenter.z();

        if (movedToAnotherChunk) {
            lastCenterChunk = currentCenter;
            enqueueNearbyChunks(client, false);
            OreCache.removeFarPositions(client);
            removeFarScannedChunkKeys(client);
        }

        processCompletedScans(client);
        processBlockUpdates(client);

        if (tickCounter % MoonsConfig.INVALID_CACHE_CLEAN_INTERVAL_TICKS == 0) {
            OreCache.removeInvalidPositions(client);
            OreCache.removeFarPositions(client);
            removeFarScannedChunkKeys(client);
        }

        if (tickCounter % MoonsConfig.CHAT_REPORT_INTERVAL_TICKS == 0) {
            reportFoundTargets(client);
        }

        processScanQueue(client, MoonsConfig.CHUNKS_PER_TICK);
    }

    public static void requestFullRescan(Minecraft client) {
        if (!isClientWorldReady(client)) {
            return;
        }

        resetScannerState();
        enqueueNearbyChunks(client, true);
    }

    private static void enqueueNearbyChunks(Minecraft client, boolean forceRescan) {
        enqueueNearbyChunks(client, forceRescan, MoonsConfig.SCAN_RADIUS_CHUNKS);
    }

    public static boolean isAutoScanEnabled() {
        return AUTO_SCAN.get();
    }

    public static void setAutoScanEnabled(Minecraft client, boolean enabled) {
        if (AUTO_SCAN.get() != enabled) {
            toggleAutoScan(client);
        }
    }

    private static void enqueueNearbyChunks(Minecraft client, boolean forceRescan, int radius) {
        if (!isClientWorldReady(client)) {
            return;
        }

        ChunkPos center = client.player.chunkPosition();

        for (int chunkX = center.x() - radius; chunkX <= center.x() + radius; chunkX++) {
            for (int chunkZ = center.z() - radius; chunkZ <= center.z() + radius; chunkZ++) {
                long key = ChunkKey.pack(chunkX, chunkZ);

                if (!forceRescan && SCANNED_CHUNKS.contains(key)) {
                    continue;
                }

                if (QUEUED_CHUNKS.add(key)) {
                    SCAN_QUEUE.add(new ChunkPos(chunkX, chunkZ));
                }
            }
        }
    }

    private static void processScanQueue(Minecraft client, int maxChunks) {
        ClientLevel level = client.level;
        if (level == null || client.player == null) return;
        int generation = SCAN_GENERATION.get();
        int submitted = 0;
        while (submitted < maxChunks
                && IN_FLIGHT.get() + COMPLETED_SCANS.size() + (currentScan == null ? 0 : 1)
                        < maxChunks) {
            ChunkPos chunkPos = SCAN_QUEUE.poll();
            if (chunkPos == null) break;
            long key = ChunkKey.pack(chunkPos.x(), chunkPos.z());
            QUEUED_CHUNKS.remove(key);
            LevelChunk chunk = level.getChunkSource().getChunk(chunkPos.x(), chunkPos.z(), false);
            if (chunk == null) continue;
            IN_FLIGHT.incrementAndGet();
            submitted++;
            // The expensive column walk is kept off the render/client tick thread.
            SCAN_EXECUTOR.execute(
                    () -> {
                        try {
                            List<BlockPos> positions =
                                    scanChunk(chunk, chunkPos.x(), chunkPos.z(), generation);
                            if (positions != null && generation == SCAN_GENERATION.get()) {
                                COMPLETED_SCANS.add(
                                        new ScanBatch(level, generation, key, positions));
                            }
                        } finally {
                            IN_FLIGHT.decrementAndGet();
                        }
                    });
        }
    }

    private static List<BlockPos> scanChunk(
            LevelChunk chunk, int chunkX, int chunkZ, int generation) {
        List<BlockPos> positions = new ArrayList<>();

        int startX = chunkX << 4;
        int startZ = chunkZ << 4;

        int bottomY = chunk.getMinY();
        int topYExclusive = bottomY + chunk.getHeight();

        BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();

        for (int localX = 0; localX < 16; localX++) {
            if (Thread.currentThread().isInterrupted() || generation != SCAN_GENERATION.get())
                return null;
            int worldX = startX + localX;

            for (int localZ = 0; localZ < 16; localZ++) {
                int worldZ = startZ + localZ;

                for (int y = bottomY; y < topYExclusive; y++) {
                    mutablePos.set(worldX, y, worldZ);

                    BlockState state = chunk.getBlockState(mutablePos);
                    if (XrayBlockTarget.findEnabledTarget(state) != null) {
                        positions.add(mutablePos.immutable());
                    }
                }
            }
        }

        return positions;
    }

    /** Apply a bounded amount of work on the client thread, after checking the world identity. */
    private static void processCompletedScans(Minecraft client) {
        ClientLevel level = client.level;
        if (level == null) return;
        int processed = 0;
        while (processed < MoonsConfig.SCAN_RESULTS_PER_TICK) {
            if (currentScan == null) {
                currentScan = COMPLETED_SCANS.poll();
                currentScanIndex = 0;
            }
            if (currentScan == null) return;
            if (currentScan.level() != level || currentScan.generation() != SCAN_GENERATION.get()) {
                currentScan = null;
                continue;
            }
            if (currentScanIndex == currentScan.positions().size()) {
                SCANNED_CHUNKS.add(currentScan.chunkKey());
                currentScan = null;
                continue;
            }
            BlockPos pos = currentScan.positions().get(currentScanIndex++);
            recordTargetIfPresent(client, pos, level.getBlockState(pos));
            processed++;
        }
    }

    private record ScanBatch(
            ClientLevel level, int generation, long chunkKey, List<BlockPos> positions) {}

    private static void recordTargetIfPresent(Minecraft client, BlockPos pos, BlockState state) {
        OreCache.removeStaleStateTarget(pos, state);
        XrayTarget target = XrayBlockTarget.findEnabledTarget(state);

        if (target == null) {
            return;
        }

        if (target == XrayBlockTarget.DIAMOND
                && pos.getY() >= MoonsConfig.DIAMOND_SCAN_MAX_Y_EXCLUSIVE) {
            return;
        }

        recordTarget(client, pos, target);
    }

    private static void recordTarget(Minecraft client, BlockPos pos, XrayTarget target) {
        BlockPos foundPos = pos.immutable();

        if (target == XrayBlockTarget.DIAMOND
                && !XrayCoverMode.shouldRecordDiamond(client, foundPos)) {
            return;
        }

        if (!OreCache.add(foundPos, target)) {
            return;
        }

        printNewTarget(client, foundPos, target);
    }

    private static void printNewTarget(Minecraft client, BlockPos pos, XrayTarget target) {
        if (target == XrayBlockTarget.DIAMOND && isClientWorldReady(client)) {
            double distance = distanceToPlayer(client, pos);
            String message =
                    "New diamond: x="
                            + pos.getX()
                            + ", y="
                            + pos.getY()
                            + ", z="
                            + pos.getZ()
                            + ", distance="
                            + String.format("%.1f", distance);

            client.execute(
                    () -> {
                        if (isClientWorldReady(client)) {
                            ClientChat.send(client, message);
                        }
                    });
            return;
        }

        FOUND_TARGETS.incrementAndGet();
    }

    private static void reportFoundTargets(Minecraft client) {
        int found = FOUND_TARGETS.getAndSet(0);

        if (found <= 0 || !isClientWorldReady(client)) {
            return;
        }

        ClientChat.actionBar(
                client,
                "Scan  ·  +"
                        + found
                        + " target"
                        + (found == 1 ? "" : "s")
                        + "  ·  cache "
                        + OreCache.size());
    }

    /**
     * Called by the block-update hook whenever a block changes on the client.
     */
    public static void queueBlockUpdate(BlockPos pos) {
        if (pos == null || !AUTO_SCAN.get()) {
            return;
        }

        synchronized (PENDING_UPDATES) {
            if (PENDING_UPDATES.size() < MAX_QUEUED_UPDATES) {
                PENDING_UPDATES.add(pos.immutable());
            }
        }
    }

    private static void processBlockUpdates(Minecraft client) {
        List<BlockPos> batch = new ArrayList<>();

        synchronized (PENDING_UPDATES) {
            Iterator<BlockPos> iterator = PENDING_UPDATES.iterator();

            while (iterator.hasNext() && batch.size() < MAX_UPDATES_PER_TICK) {
                batch.add(iterator.next());
                iterator.remove();
            }
        }

        if (batch.isEmpty()) {
            return;
        }

        for (BlockPos pos : batch) {
            scanUpdatedPosition(client, pos);
        }
    }

    private static void scanUpdatedPosition(Minecraft client, BlockPos pos) {
        if (!isClientWorldReady(client)) {
            return;
        }

        BlockState state = client.level.getBlockState(pos);

        if (state.isAir()) {
            OreCache.removePosition(pos);
            return;
        }

        recordTargetIfPresent(client, pos, state);

        BlockPos.MutableBlockPos neighbor = new BlockPos.MutableBlockPos();

        for (Direction direction : Direction.values()) {
            neighbor.set(pos).move(direction);
            BlockState neighborState = client.level.getBlockState(neighbor);

            if (!neighborState.isAir()) {
                recordTargetIfPresent(client, neighbor, neighborState);
            }
        }
    }

    private static void clearPendingUpdates() {
        synchronized (PENDING_UPDATES) {
            PENDING_UPDATES.clear();
        }
    }

    public static void clearAll(Minecraft client) {
        int oldSize = OreCache.size();

        OreCache.clear();
        resetScannerState();

        ClientChat.send(client, "Cleared cache=" + oldSize + ", queue=0.");
    }

    private static void resetScannerState() {
        SCAN_GENERATION.incrementAndGet();
        COMPLETED_SCANS.clear();
        currentScan = null;
        currentScanIndex = 0;
        tickCounter = 0;
        lastCenterChunk = null;
        SCAN_QUEUE.clear();
        QUEUED_CHUNKS.clear();
        SCANNED_CHUNKS.clear();
        clearPendingUpdates();
    }

    /** Stops the module-owned worker so its class loader can be reclaimed. */
    public static void shutdown() {
        resetScannerState();
        PluginXrayTargets.resetIndex();
        OreCache.clear();
        SCAN_EXECUTOR.shutdownNow();
        try {
            if (!SCAN_EXECUTOR.awaitTermination(2, TimeUnit.SECONDS)) {
                System.err.println("[client] Ore scanner worker did not stop within 2 seconds");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void removeFarScannedChunkKeys(Minecraft client) {
        if (!isClientWorldReady(client)) {
            return;
        }

        ChunkPos center = client.player.chunkPosition();
        int maxDistance = MoonsConfig.SCAN_RADIUS_CHUNKS + 2;

        SCANNED_CHUNKS.removeIf(
                key -> {
                    int chunkX = ChunkKey.unpackX(key);
                    int chunkZ = ChunkKey.unpackZ(key);

                    int dx = Math.abs(chunkX - center.x());
                    int dz = Math.abs(chunkZ - center.z());

                    return dx > maxDistance || dz > maxDistance;
                });
    }

    private static double distanceToPlayer(Minecraft client, BlockPos pos) {
        return BlockDistance.toBlock(client, pos);
    }

    public static boolean isClientWorldReady(Minecraft client) {
        return client != null && client.level != null && client.player != null;
    }
}
