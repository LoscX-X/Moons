package com.blanoir.moons.client.module.impl.world.structure;

import com.blanoir.moons.client.access.MinecraftClientAccess;
import com.blanoir.moons.client.chat.ClientChat;
import com.blanoir.moons.client.compat.math.VecMath;
import com.blanoir.moons.client.config.settings.BooleanSetting;
import com.blanoir.moons.client.config.settings.DoubleSetting;
import com.blanoir.moons.client.config.settings.IntSetting;
import com.blanoir.moons.client.event.EventBus;
import com.blanoir.moons.client.event.frame.WorldRenderEvent;
import com.blanoir.moons.client.module.impl.render.xray.OreHighlighter;
import com.blanoir.moons.client.render.StructureLabelRenderer;
import com.blanoir.moons.client.render.WorldOverlayRenderer;
import com.blanoir.moons.client.utils.world.ChunkKey;
import com.blanoir.moons.client.utils.world.LegacyWorld;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.network.play.server.S21PacketChunkData;
import net.minecraft.network.play.server.S26PacketMapChunkBulk;
import net.minecraft.util.Vec3;
import net.minecraft.world.ChunkCoordIntPair;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class StructureLocate {
    private static final BooleanSetting ENABLED = bool("enabled", false);
    public static final BooleanSetting STRONGHOLD = bool("stronghold", true);
    public static final BooleanSetting NETHER_PORTAL = bool("netherPortal", true);
    public static final BooleanSetting SPAWNER = bool("spawner", true);
    public static final BooleanSetting DUNGEON = bool("dungeon", true);
    public static final BooleanSetting BOX = bool("box", true);
    public static final BooleanSetting NAMETAG = bool("nametag", true),
            HIDE_VISITED = bool("hideVisited", true);
    public static final IntSetting RANGE =
            new IntSetting.Builder()
                    .name("structurelocate.range")
                    .defaultValue(256)
                    .range(32, 512)
                    .build();
    public static final IntSetting DELAY =
            new IntSetting.Builder()
                    .name("structurelocate.delay")
                    .defaultValue(15)
                    .range(5, 120)
                    .build();
    public static final IntSetting TEXT_ALPHA =
            new IntSetting.Builder()
                    .name("structurelocate.textAlpha")
                    .defaultValue(128)
                    .range(0, 255)
                    .build();
    public static final DoubleSetting TEXT_SCALE =
            new DoubleSetting.Builder()
                    .name("structurelocate.textScale")
                    .defaultValue(1)
                    .range(.1, 3)
                    .build();
    private static final ExecutorService WORKER =
            Executors.newSingleThreadExecutor(
                    com.blanoir.moons.client.threads.ThreadFactories.daemon(
                            "Moons-structure-scanner"));
    private static boolean groupingFinal;
    private static StructureScanResults.Snapshot groupingSnapshot;
    private static final StructureChunkCache CHUNKS = new StructureChunkCache();
    private static boolean refreshRequested;
    private static WorldClient level;
    private static Scan scan;
    private static CompletableFuture<List<StructureEvidence.Found>> grouping;
    private static int ticks, nextScan, lastChunkX, lastChunkZ;
    private static List<StructureEvidence.Found> found = List.of();

    private StructureLocate() {}

    private static BooleanSetting bool(String key, boolean value) {
        return new BooleanSetting.Builder()
                .name("structurelocate." + key)
                .defaultValue(value)
                .build();
    }

    public static void init() {
        EventBus.CLIENT_CONTEXT_CHANGED.register("StructureLocate.context", event -> reset());
        EventBus.TICK_END.register("StructureLocate.tick", event -> tick(event.client()));
        EventBus.WORLD_RENDER.register("StructureLocate.render", StructureLocate::render);
        EventBus.BLOCK_UPDATE_POST.register(
                "StructureLocate.block",
                event -> {
                    if (isEnabled()
                            && event.applied()
                            && event.level() == level
                            && event.previousState() != event.requestedState())
                        invalidate(event.position().getX() >> 4, event.position().getZ() >> 4);
                });
        EventBus.PACKET_RECEIVE_APPLY.register(
                "StructureLocate.chunk",
                event -> {
                    if (!isEnabled()) return;
                    if (event.packet() instanceof S21PacketChunkData packet) {
                        invalidate(packet.getChunkX(), packet.getChunkZ());
                    } else if (event.packet() instanceof S26PacketMapChunkBulk packet) {
                        for (int i = 0; i < packet.getChunkCount(); i++)
                            invalidate(packet.getChunkX(i), packet.getChunkZ(i));
                    }
                });
    }

    private static void invalidate(int x, int z) {
        long key = ChunkKey.pack(x, z);
        CHUNKS.invalidate(key);
        if (scan != null) scan.results.invalidate(key);
        refreshRequested = true;
        nextScan = Math.min(nextScan, ticks + 10);
    }

    public static boolean isEnabled() {
        return ENABLED.get();
    }

    public static int setEnabled(Minecraft client, boolean value) {
        ENABLED.set(value);
        reset();
        ClientChat.send(
                client,
                "StructureLocate " + (value ? "enabled" : "disabled") + ". Scans loaded chunks.");
        return 1;
    }

    public static void reset() {
        CHUNKS.clear();
        refreshRequested = false;
        level = null;
        if (scan != null) scan.cancelled = true;
        scan = null;
        grouping = null;
        groupingSnapshot = null;
        found = List.of();
        ticks = nextScan = 0;
    }

    public static void rescan() {
        CHUNKS.clear();
        refreshRequested = false;
        if (scan != null) scan.cancelled = true;
        scan = null;
        grouping = null;
        groupingSnapshot = null;
        nextScan = 0;
    }

    public static int changeTarget(BooleanSetting setting, boolean value) {
        setting.set(value);
        found = List.of();
        rescan();
        return 1;
    }

    public static String statusText() {
        return found.size()
                + " found"
                + (grouping != null
                        ? " | grouping"
                        : scan == null
                                ? ""
                                : " | scanning " + scan.chunkIndex + "/" + scan.chunks.size());
    }

    public static String hudTag() {
        return found.size() + " found";
    }

    private static EnumSet<StructureEvidence.Kind> types() {
        var result = EnumSet.noneOf(StructureEvidence.Kind.class);
        if (STRONGHOLD.get()) result.add(StructureEvidence.Kind.STRONGHOLD);
        if (NETHER_PORTAL.get()) result.add(StructureEvidence.Kind.NETHER_PORTAL);
        if (SPAWNER.get()) result.add(StructureEvidence.Kind.SPAWNER);
        if (DUNGEON.get()) result.add(StructureEvidence.Kind.DUNGEON);
        return result;
    }

    private static void tick(Minecraft client) {
        if (!isEnabled() || client.theWorld == null || client.thePlayer == null) {
            if (level != null) reset();
            return;
        }
        if (client.theWorld != level) {
            reset();
            level = client.theWorld;
        }
        ticks++;
        int x = client.thePlayer.getPosition().getX() >> 4,
                z = client.thePlayer.getPosition().getZ() >> 4;
        Vec3 player = VecMath.position(client.thePlayer);
        double range = RANGE.get() + 64;
        found =
                found.stream()
                        .filter(f -> f.center().squareDistanceTo(player) <= range * range)
                        .map(f -> f.bounds().isVecInside(player) ? f.visit() : f)
                        .toList();
        if (scan != null && (Math.abs(x - scan.centerX) > 2 || Math.abs(z - scan.centerZ) > 2)) {
            scan.cancelled = true;
            scan = null;
            grouping = null;
            groupingSnapshot = null;
        }
        if (grouping != null && grouping.isDone()) {
            boolean current = scan != null && scan.results.accepts(groupingSnapshot);
            if (current && !grouping.isCompletedExceptionally()) {
                var incoming = grouping.join();
                if (!groupingFinal) incoming = mergePartial(incoming, found, scan.results);
                found = StructureEvidence.carryVisits(incoming, found, player);
            }
            grouping = null;
            groupingSnapshot = null;
            if (groupingFinal && current) {
                scan = null;
                nextScan = ticks + (refreshRequested ? 10 : DELAY.get() * 20);
            }
        }
        if (scan == null
                && grouping == null
                && (ticks >= nextScan || x != lastChunkX || z != lastChunkZ)) {
            int radius = RANGE.get() >> 4;
            CHUNKS.retain(
                    key ->
                            Math.abs(ChunkKey.unpackX(key) - x) <= radius
                                    && Math.abs(ChunkKey.unpackZ(key) - z) <= radius);
            refreshRequested = false;
            scan = new Scan(x, z, RANGE.get(), types(), false);
            lastChunkX = x;
            lastChunkZ = z;
        }
        if (scan == null) return;
        boolean complete = scan.step(level);
        if (grouping == null
                && (complete
                        || scan.results.revision() > scan.publishedRevision
                                && scan.results.revision() >= 9
                                && ticks >= scan.nextPublish)) {
            var owner = scan;
            var snapshot = scan.results.snapshot();
            groupingSnapshot = snapshot;
            var enabled = EnumSet.copyOf(scan.enabled);
            scan.publishedRevision = scan.results.revision();
            scan.nextPublish = ticks + 10;
            groupingFinal = complete;
            grouping =
                    CompletableFuture.supplyAsync(
                            () -> {
                                if (owner.cancelled) return List.of();
                                var result =
                                        new ArrayList<>(
                                                StructureEvidence.locate(
                                                        snapshot.markers(), enabled));
                                return List.copyOf(result);
                            },
                            WORKER);
        }
    }

    private static List<StructureEvidence.Found> mergePartial(
            List<StructureEvidence.Found> incoming,
            List<StructureEvidence.Found> previous,
            StructureScanResults results) {
        var result = new ArrayList<>(incoming);
        for (var old : previous) {
            if (!results.canRetain(old)) continue;
            boolean replaced = false;
            for (var fresh : incoming)
                if (old.kind() == fresh.kind()
                        && old.bounds().intersectsWith(LegacyWorld.inflate(fresh.bounds(), 2))) {
                    replaced = true;
                    break;
                }
            if (!replaced && result.size() < 512) result.add(old);
        }
        return List.copyOf(result);
    }

    static boolean shouldDisplay() {
        return isEnabled() && OreHighlighter.isDisplayEnabled();
    }

    private static void render(WorldRenderEvent event) {
        Minecraft client = Minecraft.getMinecraft();
        if (!shouldDisplay()
                || client.theWorld != level
                || client.thePlayer == null
                || MinecraftClientAccess.isHudHidden(client)) return;
        var camera = MinecraftClientAccess.camera(client);
        Vec3 eye = camera.position();
        List<WorldOverlayRenderer.ColoredBox> boxes = new ArrayList<>();
        List<StructureLabelRenderer.Label> labels = new ArrayList<>();
        for (var entry : found) {
            if (HIDE_VISITED.get() && entry.visited()) continue;
            if (entry.center().squareDistanceTo(VecMath.position(client.thePlayer))
                    > (double) RANGE.get() * RANGE.get()) continue;
            int color = entry.kind().color;
            var box = LegacyWorld.inflate(entry.bounds(), .15);
            if (BOX.get())
                boxes.add(
                        new WorldOverlayRenderer.ColoredBox(
                                (float) box.minX,
                                (float) box.minY,
                                (float) box.minZ,
                                (float) box.maxX,
                                (float) box.maxY,
                                (float) box.maxZ,
                                (color >> 16 & 255) / 255f,
                                (color >> 8 & 255) / 255f,
                                (color & 255) / 255f,
                                .22f));
            if (NAMETAG.get())
                labels.add(
                        new StructureLabelRenderer.Label(
                                new Vec3(
                                        entry.center().xCoord,
                                        box.maxY + .6,
                                        entry.center().zCoord),
                                entry.label()
                                        + coordinates(entry)
                                        + " | "
                                        + Math.round(
                                                entry.center()
                                                        .distanceTo(
                                                                VecMath.position(client.thePlayer)))
                                        + "m"
                                        + (entry.markers() == 0
                                                ? " | Shape"
                                                : " | " + entry.markers()),
                                color));
        }
        var pose = event.poseStack();
        pose.pushPose();
        try {
            pose.translate(-eye.xCoord, -eye.yCoord, -eye.zCoord);
            WorldOverlayRenderer.renderStyled(client, pose, boxes, "structure boxes");
        } finally {
            pose.popPose();
        }
        StructureLabelRenderer.render(client, pose, labels, TEXT_SCALE.get(), TEXT_ALPHA.get());
    }

    private static String coordinates(StructureEvidence.Found entry) {
        boolean room = entry.kind() == StructureEvidence.Kind.DUNGEON;
        if (!room && entry.kind() != StructureEvidence.Kind.SPAWNER) return "";
        Vec3 center = entry.center();
        int y = (int) Math.floor(room ? entry.bounds().minY + 1 : center.yCoord);
        return " | "
                + (room ? "~ " : "")
                + (int) Math.floor(center.xCoord)
                + ", "
                + y
                + ", "
                + (int) Math.floor(center.zCoord);
    }

    private static final class Scan {
        final int centerX, centerZ;
        final EnumSet<StructureEvidence.Kind> enabled;
        final boolean shapeEnabled;
        final List<ChunkCoordIntPair> chunks = new ArrayList<>();
        final StructureScanResults results = new StructureScanResults();
        final ArrayDeque<Job> jobs = new ArrayDeque<>();
        int chunkIndex, publishedRevision, nextPublish;
        volatile boolean cancelled;

        record Job(
                long key,
                StructureChunkCache.Entry entry,
                CompletableFuture<StructureChunkScanner.Result> future) {}

        Scan(
                int x,
                int z,
                int range,
                EnumSet<StructureEvidence.Kind> enabled,
                boolean shapeEnabled) {
            centerX = x;
            centerZ = z;
            this.enabled = enabled;
            this.shapeEnabled = false;
            if (enabled.isEmpty()) return;
            int radius = range >> 4;
            for (int dx = -radius; dx <= radius; dx++)
                for (int dz = -radius; dz <= radius; dz++)
                    chunks.add(new ChunkCoordIntPair(x + dx, z + dz));
            chunks.sort(
                    Comparator.comparingInt(
                            p ->
                                    (p.chunkXPos - x) * (p.chunkXPos - x)
                                            + (p.chunkZPos - z) * (p.chunkZPos - z)));
        }

        boolean step(WorldClient level) {
            long deadline = System.nanoTime() + 1_000_000L;
            while (!jobs.isEmpty() && jobs.getFirst().future.isDone()) {
                var job = jobs.removeFirst();
                if (!CHUNKS.isCurrent(job.key, job.entry)) continue;
                if (job.future.isCompletedExceptionally()) {
                    CHUNKS.invalidate(job.key);
                    refreshRequested = true;
                    continue;
                }
                var result = job.future.join();
                if (result == null) continue;
                CHUNKS.complete(job.key, job.entry, result.markers(), result.cavity());
                results.append(job.key, result.markers(), result.cavity());
            }
            int copied = 0, visited = 0;
            // Main-thread work is bounded palette copies, never the entire block walk.
            while (chunkIndex < chunks.size()
                    && jobs.size() < 16
                    && copied < 8
                    && visited++ < 128) {
                if (System.nanoTime() >= deadline) break;
                var position = chunks.get(chunkIndex++);
                long key = ChunkKey.pack(position.chunkXPos, position.chunkZPos);
                var chunk =
                        level.getChunkProvider().chunkExists(position.chunkXPos, position.chunkZPos)
                                ? level.getChunkFromChunkCoords(
                                        position.chunkXPos, position.chunkZPos)
                                : null;
                if (chunk == null) {
                    CHUNKS.invalidate(key);
                    continue;
                }
                var entry = CHUNKS.claim(key, chunk);
                if (entry.markers != null) {
                    results.append(key, entry.markers, entry.cavity);
                    continue;
                }
                var sections =
                        StructureChunkScanner.capture(
                                chunk.getBlockStorageArray(), 0, enabled, shapeEnabled);
                var future =
                        CompletableFuture.supplyAsync(
                                () ->
                                        StructureChunkScanner.scan(
                                                position.chunkXPos,
                                                position.chunkZPos,
                                                sections,
                                                enabled,
                                                shapeEnabled,
                                                () -> cancelled),
                                WORKER);
                jobs.addLast(new Job(key, entry, future));
                copied++;
            }
            return chunkIndex == chunks.size() && jobs.isEmpty();
        }
    }
}
