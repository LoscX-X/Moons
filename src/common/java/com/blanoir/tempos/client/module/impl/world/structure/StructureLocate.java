package com.blanoir.tempos.client.module.impl.world.structure;

import static com.blanoir.tempos.client.module.framework.SettingFactory.bool;

import com.blanoir.tempos.api.ScopedResources;
import com.blanoir.tempos.client.access.MinecraftClientAccess;
import com.blanoir.tempos.client.access.PacketAccess;
import com.blanoir.tempos.client.chat.ClientChat;
import com.blanoir.tempos.client.config.feature.HighlightDisplay;
import com.blanoir.tempos.client.config.settings.BooleanSetting;
import com.blanoir.tempos.client.config.settings.DoubleSetting;
import com.blanoir.tempos.client.config.settings.IntSetting;
import com.blanoir.tempos.client.event.EventBus;
import com.blanoir.tempos.client.event.frame.WorldRenderEvent;
import com.blanoir.tempos.client.module.framework.ClientFeature;
import com.blanoir.tempos.client.module.framework.ModuleCategory;
import com.blanoir.tempos.client.module.framework.ModuleInfo;
import com.blanoir.tempos.client.module.framework.ModuleModel;
import com.blanoir.tempos.client.module.framework.ModuleModel.Setting;
import com.blanoir.tempos.client.render.StructureLabelRenderer;
import com.blanoir.tempos.client.render.WorldOverlayRenderer;
import com.blanoir.tempos.client.concurrent.TaskScope;
import com.blanoir.tempos.client.utils.world.ChunkKey;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@ModuleInfo(
        category = ModuleCategory.WORLD,
        description = "Highlights evidence of selected structures within the scan range.")
public final class StructureLocate extends ClientFeature {
    public static final StructureLocate INSTANCE = new StructureLocate();

    private final BooleanSetting enabled = bool("enabled", false);
    public final BooleanSetting ancientCity = bool("ancientCity", true);
    public final BooleanSetting stronghold = bool("stronghold", true);
    public final BooleanSetting trialChamber = bool("trialChamber", true);
    public final BooleanSetting netherPortal = bool("netherPortal", true);
    public final BooleanSetting spawner = bool("spawner", true);
    public final BooleanSetting dungeon = bool("dungeon", true);
    public final BooleanSetting amethystGeode = bool("amethystGeode", true);
    public final BooleanSetting geodeShape = bool("geodeShape", true);
    public final BooleanSetting box = bool("box", true);
    public final BooleanSetting nametag = bool("nametag", true),
            hideVisited = bool("hideVisited", true);
    public final IntSetting range =
            new IntSetting.Builder()
                    .name("structurelocate.range")
                    .defaultValue(256)
                    .range(32, 512)
                    .build();
    public final IntSetting delay =
            new IntSetting.Builder()
                    .name("structurelocate.delay")
                    .defaultValue(15)
                    .range(5, 120)
                    .build();
    public final IntSetting textAlpha =
            new IntSetting.Builder()
                    .name("structurelocate.textAlpha")
                    .defaultValue(128)
                    .range(0, 255)
                    .build();
    public final DoubleSetting textScale =
            new DoubleSetting.Builder()
                    .name("structurelocate.textScale")
                    .defaultValue(1)
                    .range(.1, 3)
                    .build();
    private final TaskScope worker =
            TaskScope.serial("structure scanner", "Tempos-structure-scanner", Integer.MAX_VALUE);
    private boolean groupingFinal;
    private StructureScanResults.Snapshot groupingSnapshot;
    private final StructureChunkCache chunks = new StructureChunkCache();
    private boolean refreshRequested;
    private ClientLevel level;
    private Scan scan;
    private CompletableFuture<List<StructureEvidence.Found>> grouping;
    private int ticks, nextScan, lastChunkX, lastChunkZ;
    private List<StructureEvidence.Found> found = List.of();

    private StructureLocate() {
        super("structurelocate", "StructureLocate");
    }

    private static BooleanSetting bool(String key, boolean value) {
        return new BooleanSetting.Builder()
                .name("structurelocate." + key)
                .defaultValue(value)
                .build();
    }

    @Override
    public void registerEvents() {
        ScopedResources.own(this.worker);
        EventBus.CLIENT_CONTEXT_CHANGED.register("StructureLocate.context", event -> reset());
        EventBus.TICK_END.register("StructureLocate.tick", event -> tick(event.client()));
        EventBus.WORLD_RENDER.register("StructureLocate.render", this::render);
        EventBus.BLOCK_UPDATE_POST.register(
                "StructureLocate.block",
                event -> {
                    if (isEnabled()
                            && event.applied()
                            && event.level() == this.level
                            && event.previousState() != event.requestedState())
                        invalidate(event.position().getX() >> 4, event.position().getZ() >> 4);
                });
        EventBus.PACKET_RECEIVE_APPLY.register(
                "StructureLocate.chunk",
                event -> {
                    if (!isEnabled()) return;
                    if (event.packet() instanceof ClientboundLevelChunkWithLightPacket packet) {
                        var position = PacketAccess.chunkPosition(packet);
                        invalidate(position.x(), position.z());
                    } else if (event.packet() instanceof ClientboundForgetLevelChunkPacket packet)
                        invalidate(packet.pos().x(), packet.pos().z());
                });
    }

    public void shutdown() {
        reset();
        this.worker.close();
    }

    public void suspend() {
        reset();
        this.worker.cancelOutstanding();
    }

    private void invalidate(int x, int z) {
        long key = ChunkKey.pack(x, z);
        this.chunks.invalidate(key);
        if (this.scan != null) this.scan.results.invalidate(key);
        this.refreshRequested = true;
        this.nextScan = Math.min(this.nextScan, this.ticks + 10);
    }

    @Override
    public boolean isEnabled() {
        return this.enabled.get();
    }

    @Override
    public int setEnabled(Minecraft client, boolean value) {
        this.enabled.set(value);
        reset();
        ClientChat.send(
                client,
                "StructureLocate " + (value ? "enabled" : "disabled") + ". Scans loaded chunks.");
        return 1;
    }

    public void reset() {
        this.chunks.clear();
        this.refreshRequested = false;
        this.level = null;
        if (this.scan != null) this.scan.cancelled = true;
        this.scan = null;
        this.grouping = null;
        this.groupingSnapshot = null;
        this.found = List.of();
        this.ticks = this.nextScan = 0;
    }

    public void rescan() {
        this.chunks.clear();
        this.refreshRequested = false;
        if (this.scan != null) this.scan.cancelled = true;
        this.scan = null;
        this.grouping = null;
        this.groupingSnapshot = null;
        this.nextScan = 0;
    }

    public int changeTarget(BooleanSetting setting, boolean value) {
        setting.set(value);
        this.found = List.of();
        rescan();
        return 1;
    }

    public String statusText() {
        return this.found.size()
                + " found"
                + (this.grouping != null
                        ? " | grouping"
                        : this.scan == null
                                ? ""
                                : " | scanning "
                                        + this.scan.chunkIndex
                                        + "/"
                                        + this.scan.chunks.size());
    }

    public String hudTag() {
        return this.found.size() + " found";
    }

    private EnumSet<StructureEvidence.Kind> types() {
        var result = EnumSet.noneOf(StructureEvidence.Kind.class);
        if (this.ancientCity.get()) result.add(StructureEvidence.Kind.ANCIENT_CITY);
        if (this.stronghold.get()) result.add(StructureEvidence.Kind.STRONGHOLD);
        if (this.trialChamber.get()) result.add(StructureEvidence.Kind.TRIAL_CHAMBER);
        if (this.netherPortal.get()) result.add(StructureEvidence.Kind.NETHER_PORTAL);
        if (this.spawner.get()) result.add(StructureEvidence.Kind.SPAWNER);
        if (this.dungeon.get()) result.add(StructureEvidence.Kind.DUNGEON);
        if (this.amethystGeode.get()) result.add(StructureEvidence.Kind.AMETHYST_GEODE);
        return result;
    }

    private void tick(Minecraft client) {
        if (!isEnabled() || client.level == null || client.player == null) {
            if (this.level != null) reset();
            return;
        }
        if (client.level != this.level) {
            reset();
            this.level = client.level;
        }
        this.ticks++;
        int x = client.player.blockPosition().getX() >> 4,
                z = client.player.blockPosition().getZ() >> 4;
        Vec3 player = client.player.position();
        double range = this.range.get() + 64;
        this.found =
                this.found.stream()
                        .filter(f -> f.center().distanceToSqr(player) <= range * range)
                        .map(f -> f.bounds().contains(player) ? f.visit() : f)
                        .toList();
        if (this.scan != null
                && (Math.abs(x - this.scan.centerX) > 2 || Math.abs(z - this.scan.centerZ) > 2)) {
            this.scan.cancelled = true;
            this.scan = null;
            this.grouping = null;
            this.groupingSnapshot = null;
        }
        if (this.grouping != null && this.grouping.isDone()) {
            boolean current = this.scan != null && this.scan.results.accepts(this.groupingSnapshot);
            if (current && !this.grouping.isCompletedExceptionally()) {
                var incoming = this.grouping.join();
                if (!this.groupingFinal)
                    incoming = mergePartial(incoming, this.found, this.scan.results);
                this.found = StructureEvidence.carryVisits(incoming, this.found, player);
            }
            this.grouping = null;
            this.groupingSnapshot = null;
            if (this.groupingFinal && current) {
                this.scan = null;
                this.nextScan = this.ticks + (this.refreshRequested ? 10 : this.delay.get() * 20);
            }
        }
        if (this.scan == null
                && this.grouping == null
                && (this.ticks >= this.nextScan || x != this.lastChunkX || z != this.lastChunkZ)) {
            int radius = this.range.get() >> 4;
            this.chunks.retain(
                    key ->
                            Math.abs(ChunkKey.unpackX(key) - x) <= radius
                                    && Math.abs(ChunkKey.unpackZ(key) - z) <= radius);
            this.refreshRequested = false;
            this.scan = new Scan(x, z, this.range.get(), types(), this.geodeShape.get());
            this.lastChunkX = x;
            this.lastChunkZ = z;
        }
        if (this.scan == null) return;
        boolean complete = this.scan.step(this.level);
        if (this.grouping == null
                && (complete
                        || this.scan.results.revision() > this.scan.publishedRevision
                                && this.scan.results.revision() >= 9
                                && this.ticks >= this.scan.nextPublish)) {
            var owner = this.scan;
            var snapshot = this.scan.results.snapshot();
            this.groupingSnapshot = snapshot;
            var enabled = EnumSet.copyOf(this.scan.enabled);
            this.scan.publishedRevision = this.scan.results.revision();
            this.scan.nextPublish = this.ticks + 10;
            this.groupingFinal = complete;
            this.grouping =
                    this.worker.submit(
                            () -> {
                                if (owner.cancelled) return List.of();
                                var result =
                                        new ArrayList<>(
                                                StructureEvidence.locate(
                                                        snapshot.markers(), enabled));
                                if (!owner.cancelled && owner.shapeEnabled && result.size() < 512)
                                    result.addAll(
                                            CavityEvidence.locate(
                                                    snapshot.cavities(),
                                                    result,
                                                    () -> owner.cancelled));
                                return List.copyOf(result);
                            });
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
                        && old.bounds().intersects(fresh.bounds().inflate(2))) {
                    replaced = true;
                    break;
                }
            if (!replaced && result.size() < 512) result.add(old);
        }
        return List.copyOf(result);
    }

    boolean shouldDisplay() {
        return isEnabled() && HighlightDisplay.enabled();
    }

    private void render(WorldRenderEvent event) {
        Minecraft client = Minecraft.getInstance();
        if (!shouldDisplay()
                || client.level != this.level
                || client.player == null
                || MinecraftClientAccess.isHudHidden(client)) return;
        var camera = MinecraftClientAccess.camera(client);
        Vec3 eye = camera.position();
        List<WorldOverlayRenderer.ColoredBox> boxes = new ArrayList<>();
        List<StructureLabelRenderer.Label> labels = new ArrayList<>();
        for (var entry : this.found) {
            if (this.hideVisited.get() && entry.visited()) continue;
            if (entry.center().distanceToSqr(client.player.position())
                    > (double) this.range.get() * this.range.get()) continue;
            int color = entry.kind().color;
            var box = entry.bounds().inflate(.15);
            if (this.box.get())
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
            if (this.nametag.get())
                labels.add(
                        new StructureLabelRenderer.Label(
                                new Vec3(entry.center().x, box.maxY + .6, entry.center().z),
                                entry.label()
                                        + coordinates(entry)
                                        + " | "
                                        + Math.round(
                                                entry.center().distanceTo(client.player.position()))
                                        + "m"
                                        + (entry.markers() == 0
                                                ? " | Shape"
                                                : " | " + entry.markers()),
                                color));
        }
        var pose = event.poseStack();
        pose.pushPose();
        try {
            pose.translate(-eye.x, -eye.y, -eye.z);
            WorldOverlayRenderer.renderStyled(client, pose, boxes, "structure boxes");
        } finally {
            pose.popPose();
        }
        StructureLabelRenderer.render(
                client, pose, labels, this.textScale.get(), this.textAlpha.get());
    }

    private static String coordinates(StructureEvidence.Found entry) {
        boolean room = entry.kind() == StructureEvidence.Kind.DUNGEON;
        if (!room && entry.kind() != StructureEvidence.Kind.SPAWNER) return "";
        Vec3 center = entry.center();
        int y = (int) Math.floor(room ? entry.bounds().minY + 1 : center.y);
        return " | "
                + (room ? "~ " : "")
                + (int) Math.floor(center.x)
                + ", "
                + y
                + ", "
                + (int) Math.floor(center.z);
    }

    private static final class Scan {
        final int centerX, centerZ;
        final EnumSet<StructureEvidence.Kind> enabled;
        final boolean shapeEnabled;
        final List<ChunkPos> chunks = new ArrayList<>();
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
            this.shapeEnabled =
                    shapeEnabled && enabled.contains(StructureEvidence.Kind.AMETHYST_GEODE);
            if (enabled.isEmpty()) return;
            int radius = range >> 4;
            for (int dx = -radius; dx <= radius; dx++)
                for (int dz = -radius; dz <= radius; dz++) chunks.add(new ChunkPos(x + dx, z + dz));
            chunks.sort(
                    Comparator.comparingInt(
                            p -> (p.x() - x) * (p.x() - x) + (p.z() - z) * (p.z() - z)));
        }

        boolean step(ClientLevel level) {
            long deadline = System.nanoTime() + 1_000_000L;
            while (!jobs.isEmpty() && jobs.getFirst().future.isDone()) {
                var job = jobs.removeFirst();
                if (!StructureLocate.INSTANCE.chunks.isCurrent(job.key, job.entry)) continue;
                if (job.future.isCompletedExceptionally()) {
                    StructureLocate.INSTANCE.chunks.invalidate(job.key);
                    StructureLocate.INSTANCE.refreshRequested = true;
                    continue;
                }
                var result = job.future.join();
                if (result == null) continue;
                StructureLocate.INSTANCE.chunks.complete(
                        job.key, job.entry, result.markers(), result.cavity());
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
                long key = ChunkKey.pack(position.x(), position.z());
                var chunk = level.getChunkSource().getChunk(position.x(), position.z(), false);
                if (chunk == null) {
                    StructureLocate.INSTANCE.chunks.invalidate(key);
                    continue;
                }
                var entry = StructureLocate.INSTANCE.chunks.claim(key, chunk);
                if (entry.markers != null) {
                    results.append(key, entry.markers, entry.cavity);
                    continue;
                }
                var sections =
                        StructureChunkScanner.capture(
                                chunk.getSections(), chunk.getMinY(), enabled, shapeEnabled);
                var future =
                        StructureLocate.INSTANCE.worker.submit(
                                () ->
                                        StructureChunkScanner.scan(
                                                position.x(),
                                                position.z(),
                                                sections,
                                                enabled,
                                                shapeEnabled,
                                                () -> cancelled));
                jobs.addLast(new Job(key, entry, future));
                copied++;
            }
            return chunkIndex == chunks.size() && jobs.isEmpty();
        }
    }

    @Override
    protected String statusTag() {
        return this.statusText();
    }

    @Override
    protected Setting[] defineSettings() {
        return new Setting[] {
            this.range.describe(
                    "range",
                    "Range",
                    16,
                    (c, v) -> {
                        this.range.set(v);
                        this.rescan();
                        return 1;
                    }),
            this.ancientCity.describe(
                    "ancient_city",
                    "Ancient City",
                    (c, v) -> this.changeTarget(this.ancientCity, v)),
            this.stronghold.describe(
                    "stronghold", "Stronghold", (c, v) -> this.changeTarget(this.stronghold, v)),
            this.trialChamber.describe(
                    "trial_chamber",
                    "Trial Chamber",
                    (c, v) -> this.changeTarget(this.trialChamber, v)),
            this.netherPortal.describe(
                    "nether_portal",
                    "Nether Portal",
                    (c, v) -> this.changeTarget(this.netherPortal, v)),
            this.spawner.describe(
                    "spawner", "Spawner", (c, v) -> this.changeTarget(this.spawner, v)),
            this.dungeon.describe(
                    "dungeon", "Possible dungeon", (c, v) -> this.changeTarget(this.dungeon, v)),
            this.amethystGeode.describe(
                    "amethyst_geode",
                    "Amethyst geode",
                    (c, v) -> this.changeTarget(this.amethystGeode, v)),
            this.geodeShape
                    .describe(
                            "geode_shape",
                            "Geode shape fallback",
                            (c, v) -> this.changeTarget(this.geodeShape, v))
                    .visibleWhen(this.amethystGeode::get),
            this.box.describe(
                    "box",
                    "Box",
                    (c, v) -> {
                        this.box.set(v);
                        return 1;
                    }),
            this.nametag.describe(
                    "nametag",
                    "Name tag",
                    (c, v) -> {
                        this.nametag.set(v);
                        return 1;
                    }),
            this.hideVisited.describe(
                    "hide_visited",
                    "Hide visited",
                    (c, v) -> {
                        this.hideVisited.set(v);
                        return 1;
                    }),
            this.delay.describe(
                    "scan_delay",
                    "Scan delay (s)",
                    1,
                    (c, v) -> {
                        this.delay.set(v);
                        return 1;
                    }),
            this.textScale.describe(
                    "text_scale",
                    "Text scale",
                    .1,
                    (c, v) -> {
                        this.textScale.set(v);
                        return 1;
                    }),
            this.textAlpha.describe(
                    "text_alpha",
                    "Text background alpha",
                    1,
                    (c, v) -> {
                        this.textAlpha.set(v);
                        return 1;
                    })
        };
    }

    @Override
    public ModuleModel.Module toDescriptor() {
        return super.toDescriptor().withHudTag(this::hudTag, "888 found");
    }
}
