package com.blanoir.moons.client.module.impl.world.structure;

import static com.blanoir.moons.client.module.impl.world.structure.StructureEvidence.Kind.*;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

public final class StructureLocateVerification {
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        require(
                StructureEvidence.marker(Blocks.END_PORTAL_FRAME) == STRONGHOLD,
                "Stronghold marker mapping");
        require(
                StructureEvidence.marker(Blocks.NETHER_PORTAL) == NETHER_PORTAL,
                "Active portal mapping");
        require(
                StructureEvidence.marker(Blocks.OBSIDIAN) == null,
                "Ordinary obsidian is not a portal");
        require(
                StructureEvidence.marker(Blocks.SCULK) == null,
                "Ordinary deep dark is not a city marker");
        require(StructureEvidence.marker(Blocks.SPAWNER) == SPAWNER, "Actual spawner mapping");
        require(
                StructureEvidence.marker(Blocks.MOSSY_COBBLESTONE) == DUNGEON,
                "Mossy room evidence");
        require(StructureEvidence.marker(Blocks.COBBLESTONE) == DUNGEON, "Room masonry evidence");
        verifyDungeons();
        GeodeEvidenceVerification.run();
        CavityEvidenceVerification.run();
        VanillaGeodeVerification.run();
        verifyChunkCache();
        verifyScanInvalidation();
        StructureChunkScannerVerification.run();
        boolean oldEnabled = StructureLocate.isEnabled();
        boolean oldDisplay =
                com.blanoir.moons.client.module.impl.render.xray.OreHighlighter.isDisplayEnabled();
        try {
            StructureLocate.setEnabled(null, true);
            com.blanoir.moons.client.module.impl.render.xray.OreHighlighter.setDisplayEnabled(
                    null, false);
            require(
                    !StructureLocate.shouldDisplay() && StructureLocate.isEnabled(),
                    "Xray Display hides structures while the scanning module remains enabled");
            com.blanoir.moons.client.module.impl.render.xray.OreHighlighter.setDisplayEnabled(
                    null, true);
            require(
                    StructureLocate.shouldDisplay(),
                    "Re-enabling Xray Display restores structure rendering");
        } finally {
            StructureLocate.setEnabled(null, oldEnabled);
            com.blanoir.moons.client.module.impl.render.xray.OreHighlighter.setDisplayEnabled(
                    null, oldDisplay);
        }
        require(
                StructureEvidence.marker(Blocks.REINFORCED_DEEPSLATE) == ANCIENT_CITY,
                "Reinforced deepslate mapping");
        for (String id :
                List.of("copper_bulb", "oxidized_copper_bulb", "chiseled_tuff", "tuff_bricks"))
            require(
                    StructureEvidence.marker(
                                    BuiltInRegistries.BLOCK
                                            .getOptional(Identifier.parse("minecraft:" + id))
                                            .orElseThrow())
                            == TRIAL_CHAMBER,
                    "Trial marker " + id);
        require(
                StructureEvidence.allowedHeight(STRONGHOLD, 79)
                        && !StructureEvidence.allowedHeight(STRONGHOLD, 80),
                "Stronghold ceiling is exclusive");
        require(
                StructureEvidence.allowedHeight(ANCIENT_CITY, 8)
                        && !StructureEvidence.allowedHeight(ANCIENT_CITY, 9),
                "City ceiling");
        require(StructureEvidence.allowedHeight(NETHER_PORTAL, 220), "Portals scan all heights");

        var all = EnumSet.allOf(StructureEvidence.Kind.class);
        var frame = new StructureEvidence.Marker(STRONGHOLD, new BlockPos(-1, -40, 15));
        var nearbyTrial = new StructureEvidence.Marker(TRIAL_CHAMBER, new BlockPos(1, -40, 16));
        var farTrial = new StructureEvidence.Marker(TRIAL_CHAMBER, new BlockPos(40, -40, 16));
        var result = StructureEvidence.locate(List.of(frame, frame, nearbyTrial, farTrial), all);
        require(
                result.size() == 2 && result.stream().allMatch(f -> f.markers() == 1),
                "Deduplication and trial suppression across chunk boundaries");
        var trialOnly =
                StructureEvidence.locate(
                        List.of(frame, nearbyTrial, farTrial), EnumSet.of(TRIAL_CHAMBER));
        require(
                trialOnly.size() == 1
                        && trialOnly.getFirst().bounds().contains(Vec3.atCenterOf(farTrial.pos())),
                "Disabling stronghold display retains disambiguating frame evidence");
        require(
                StructureEvidence.locate(
                                List.of(frame), EnumSet.noneOf(StructureEvidence.Kind.class))
                        .isEmpty(),
                "Type toggles filter results");

        var portals = new ArrayList<StructureEvidence.Marker>();
        for (int x : new int[] {0, 2, 1, 40})
            portals.add(new StructureEvidence.Marker(NETHER_PORTAL, new BlockPos(x, 70, 0)));
        var portalGroups = StructureEvidence.locate(portals, all);
        require(
                portalGroups.size() == 2 && portalGroups.stream().anyMatch(f -> f.markers() == 3),
                "Bridging portal blocks merge, distant portals remain separate");
        var visited =
                StructureEvidence.carryVisits(portalGroups, List.of(), new Vec3(1.5, 70.5, .5));
        require(
                visited.stream().filter(StructureEvidence.Found::visited).count() == 1,
                "Only entered structure is visited");
        var rescanned =
                StructureEvidence.carryVisits(
                        StructureEvidence.locate(portals, all), visited, new Vec3(100, 70, 0));
        require(
                rescanned.stream().filter(StructureEvidence.Found::visited).count() == 1
                        && rescanned.stream().mapToInt(StructureEvidence.Found::markers).sum() == 4,
                "Rescans retain visits without accumulating duplicate counts");

        var city = new ArrayList<StructureEvidence.Marker>();
        for (int x : new int[] {0, 4})
            for (int y : new int[] {-50, -48})
                for (int z : new int[] {0, 4})
                    city.add(new StructureEvidence.Marker(ANCIENT_CITY, new BlockPos(x, y, z)));
        require(
                StructureEvidence.locate(city, all).size() == 1,
                "City structural extent survives aggregation");
        require(
                StructureEvidence.locate(
                                List.of(
                                        new StructureEvidence.Marker(
                                                ANCIENT_CITY, new BlockPos(0, 20, 0))),
                                all)
                        .isEmpty(),
                "Above-range marker is excluded");
        StructureLocate.reset();
        require(
                StructureLocate.statusText().equals("0 found"),
                "World reset clears structures and jobs");
        System.out.println(
                "StructureLocate marker, clustering, toggles, visits and reset verification passed.");
    }

    private static void verifyDungeons() {
        var markers = new ArrayList<StructureEvidence.Marker>();
        // Across the chunk boundary, with a corner and an internal section of floor cut away.
        for (int x = 13; x < 20; x++)
            for (int z = -3; z < 4; z++) {
                if (x >= 17 && z >= 1 || x == 15 && z == 0) continue;
                markers.add(new StructureEvidence.Marker(DUNGEON, new BlockPos(x, -30, z), true));
            }
        var dungeonOnly = EnumSet.of(DUNGEON);
        require(
                StructureEvidence.locate(markers, dungeonOnly).isEmpty(),
                "Mossy paving without a room wall is not a dungeon");
        for (int y = -29; y <= -27; y++)
            for (int z = -3; z < 4; z++)
                markers.add(new StructureEvidence.Marker(DUNGEON, new BlockPos(13, y, z)));
        var inferred = StructureEvidence.locate(markers, dungeonOnly);
        require(
                inferred.size() == 1 && inferred.getFirst().kind() == DUNGEON,
                "Partial mossy floor plus one surviving wall produces a suspected room");
        var cage = new StructureEvidence.Marker(SPAWNER, new BlockPos(16, -29, 0));
        markers.add(cage);
        var confirmed = StructureEvidence.locate(markers, EnumSet.of(SPAWNER, DUNGEON));
        require(
                confirmed.size() == 1
                        && confirmed.getFirst().kind() == SPAWNER
                        && confirmed
                                .getFirst()
                                .bounds()
                                .equals(new net.minecraft.world.phys.AABB(cage.pos())),
                "Visible spawner gets exact block bounds and suppresses duplicate room guess");
        markers.remove(cage);
        var removed = StructureEvidence.locate(markers, EnumSet.of(SPAWNER, DUNGEON));
        require(
                removed.size() == 1 && removed.getFirst().kind() == DUNGEON,
                "Removed or stone-masked cage leaves only the suspected room");
        markers.add(cage);
        markers.add(new StructureEvidence.Marker(SPAWNER, new BlockPos(17, -29, 0)));
        require(
                StructureEvidence.locate(markers, EnumSet.of(SPAWNER)).size() == 2,
                "Adjacent cages remain separate positions");
        require(
                StructureEvidence.locate(markers, EnumSet.noneOf(StructureEvidence.Kind.class))
                        .isEmpty(),
                "Disabling dungeon and spawner hides both");
        require(
                StructureEvidence.locate(
                                List.of(
                                        new StructureEvidence.Marker(
                                                DUNGEON, new BlockPos(0, -30, 0), true)),
                                dungeonOnly)
                        .isEmpty(),
                "One mossy block is insufficient");
        var tall = List.of(new StructureEvidence.Marker(SPAWNER, new BlockPos(0, 180, 0)));
        require(
                StructureEvidence.locate(tall, EnumSet.of(SPAWNER)).size() == 1,
                "Actual cages are not subject to the underground room heuristic height limit");
    }

    private static void verifyChunkCache() {
        var cache = new StructureChunkCache();
        var source = new Object();
        var entry = cache.claim(1, source);
        cache.complete(1, entry, List.of());
        require(
                cache.claim(1, source) == entry && entry.markers.isEmpty(),
                "Unchanged empty chunks are cached too");
        var replacement = cache.claim(1, new Object());
        require(
                replacement != entry && replacement.markers == null,
                "Reloaded chunks do not reuse an old snapshot");
        cache.invalidate(1);
        cache.complete(1, replacement, List.of());
        require(
                cache.claim(1, source).markers == null,
                "An update during scanning cannot publish a stale cache entry");
        cache.complete(1, cache.claim(1, source), List.of());
        for (int i = 2; i < StructureChunkCache.MAX_CHUNKS + 5; i++) cache.claim(i, source);
        require(
                cache.claim(1, source).markers == null,
                "Cache remains bounded and evicts old chunks");
        var kept = cache.claim(2, source);
        cache.complete(2, kept, List.of());
        cache.retain(key -> key == 2);
        require(
                cache.claim(2, source) == kept && cache.claim(3, source).markers == null,
                "Leaving scan range evicts chunk snapshots");
        cache.clear();
        require(cache.claim(2, source).markers == null, "World/config reset clears snapshots");
        var oversized = cache.claim(4, source);
        cache.complete(
                4,
                oversized,
                java.util.Collections.nCopies(
                        StructureChunkCache.MAX_MARKERS + 1,
                        new StructureEvidence.Marker(SPAWNER, BlockPos.ZERO)));
        require(
                cache.claim(4, source).markers == null,
                "Dense chunks cannot exceed the marker memory budget");
    }

    private static void verifyScanInvalidation() {
        var scan = new StructureScanResults();
        var marker = new StructureEvidence.Marker(SPAWNER, BlockPos.ZERO);
        var cavity = new CavitySnapshot.Builder(0, 0).build();
        scan.append(0, List.of(marker), cavity);
        var inFlight = scan.snapshot();
        scan.append(1, List.of(), null);
        require(
                scan.accepts(inFlight),
                "New chunks do not discard a valid incremental publication");
        scan.invalidate(0);
        require(
                !scan.canRetain(
                        new StructureEvidence.Found(
                                SPAWNER,
                                new net.minecraft.world.phys.AABB(BlockPos.ZERO),
                                1,
                                false)),
                "Partial merging cannot bring back an old label in an updated chunk");
        require(
                scan.canRetain(
                        new StructureEvidence.Found(
                                SPAWNER,
                                new net.minecraft.world.phys.AABB(new BlockPos(16, 0, 0)),
                                1,
                                false)),
                "An update preserves previous labels in untouched chunks");
        require(!scan.accepts(inFlight), "Updates invalidate grouping that already copied a chunk");
        require(
                scan.snapshot().markers().isEmpty() && scan.snapshot().cavities().isEmpty(),
                "Invalidated evidence cannot reappear in later partial or final publications");
        require(
                inFlight.markers().size() == 1 && inFlight.cavities().size() == 1,
                "Workers retain immutable inputs while the client invalidates a chunk");
        scan.append(0, List.of(), null);
        var refreshed = scan.snapshot();
        require(
                scan.accepts(refreshed) && refreshed.markers().isEmpty(),
                "An empty replacement chunk removes the old spawner");

        scan.append(2, java.util.Collections.nCopies(12001, marker), null);
        require(scan.snapshot().markers().size() == 12000, "Scan marker budget is enforced");
        scan.invalidate(2);
        scan.append(3, List.of(marker), null);
        require(scan.snapshot().markers().size() == 1, "Invalidation releases the marker budget");
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
