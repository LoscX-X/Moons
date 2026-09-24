package com.blanoir.moons.client.module.impl.world.structure;

import java.util.ArrayList;
import java.util.List;

final class CavityEvidenceVerification {
    @FunctionalInterface
    private interface Air {
        boolean at(int x, int y, int z);
    }

    static void run() {
        // Every non-air cell is an anonymous solid wall; no purple/calcite markers exist.
        var sphere = world((x, y, z) -> x * x + y * y + z * z < 49);
        var found = CavityEvidence.locate(sphere, List.of());
        require(
                CavityEvidence.locate(sphere, List.of(), () -> true).isEmpty(),
                "Cancelled scans skip contour work");
        var probes = new java.util.concurrent.atomic.AtomicInteger();
        require(
                CavityEvidence.locate(sphere, List.of(), () -> probes.incrementAndGet() > 100)
                                .isEmpty()
                        && probes.get() == 101,
                "Running contour work stops promptly when the world or configuration changes");
        require(
                found.size() == 1
                        && found.getFirst().tentative()
                        && found.getFirst().markers() == 0,
                "Stone-only rounded cavity crossing chunk boundaries is a shape guess");
        require(
                CavityEvidence.locate(sphere.reversed(), List.of()).equals(found),
                "Best-fit result does not depend on the first chunk/seed visited");
        var oval = world((x, y, z) -> x * x / 36.0 + y * y / 25.0 + z * z / 30.25 < 1);
        require(!CavityEvidence.locate(oval, List.of()).isEmpty(), "Moderate ellipsoid shape");
        var opened =
                world(
                        (x, y, z) ->
                                x * x + y * y + z * z < 49
                                        || x > 0 && Math.abs(y) < 2 && Math.abs(z) < 2);
        require(!CavityEvidence.locate(opened, List.of()).isEmpty(), "Small opening is allowed");
        require(
                CavityEvidence.locate(world((x, y, z) -> false), List.of()).isEmpty(),
                "An entirely stone-filled volume exposes no shape");
        require(
                CavityEvidence.locate(world((x, y, z) -> true), List.of()).isEmpty(),
                "Open air is not a chamber");
        require(
                CavityEvidence.locate(world((x, y, z) -> y * y + z * z < 25), List.of()).isEmpty(),
                "Long passage is rejected");
        require(
                CavityEvidence.locate(
                                world(
                                        (x, y, z) ->
                                                Math.abs(x) < 7
                                                        && Math.abs(y) < 7
                                                        && Math.abs(z) < 7),
                                List.of())
                        .isEmpty(),
                "Rectangular room has the wrong diagonal contour");
        require(
                CavityEvidence.locate(world((x, y, z) -> Math.abs(y) < 2), List.of()).isEmpty(),
                "Thin horizontal space is rejected");
        var partial = sphere.stream().filter(s -> s.chunkX < -1).toList();
        require(
                CavityEvidence.locate(partial, List.of()).isEmpty(),
                "Unloaded space is not treated as air or a wall");
        require(
                CavityEvidence.locate(sphere, found).isEmpty(),
                "Material or prior matches suppress duplicate shape labels");
        require(
                CavityEvidence.locate(sphere, java.util.Collections.nCopies(512, found.getFirst()))
                        .isEmpty(),
                "Contour results respect an already full display budget");
        var cut = world((x, y, z) -> x * x + y * y + z * z < 49 || x >= 2);
        require(
                !CavityEvidence.locate(cut, List.of()).isEmpty(),
                "Large side cut retains curved shell evidence");
        var diagonalCut = world((x, y, z) -> x * x + y * y + z * z < 49 || x + y + z >= 4);
        require(
                !CavityEvidence.locate(diagonalCut, List.of()).isEmpty(),
                "Oblique cut can retain enough curve directions");
        var smallRemnant = world((x, y, z) -> x * x + y * y + z * z < 49 || x >= -4);
        require(
                CavityEvidence.locate(smallRemnant, List.of()).isEmpty(),
                "A small isolated curved remnant cannot define a chamber");
        var builder = new CavitySnapshot.Builder(0, 0);
        require(
                CavityEvidence.locate(world((x, y, z) -> x * x + y * y + z * z < 144), List.of())
                        .isEmpty(),
                "Oversized rounded caves are outside the vanilla geode scale");
        require(
                CavityEvidence.locate(
                                world(
                                        (x, y, z) ->
                                                Math.abs(z) < 7
                                                        && y > Math.floorDiv(x, 3) - 4
                                                        && y < Math.floorDiv(x, 3) + 5),
                                List.of())
                        .isEmpty(),
                "Sloping cave with stepped floor and ceiling is not a geode");
        require(
                CavityEvidence.locate(
                                world(
                                        (x, y, z) ->
                                                Math.abs(x) < 9
                                                        && Math.abs(z) < 8
                                                        && y > -6 + Math.floorDiv(x + 9, 3)
                                                        && y < 8),
                                List.of())
                        .isEmpty(),
                "Terraced stone chamber lacks a continuous curved shell");
        require(
                CavityEvidence.locate(
                                world(
                                        (x, y, z) ->
                                                x * x + y * y + z * z < 81
                                                        && !(Math.abs(x) <= 2 && Math.abs(z) <= 2)),
                                List.of())
                        .isEmpty(),
                "Stone pillars inside a broad cave cannot be boxed as one clear cavity");
        require(
                CavityEvidence.locate(
                                world(
                                        (x, y, z) ->
                                                x * x + y * y + z * z < 49
                                                        || Math.abs(y) < 3 && Math.abs(z) < 3
                                                        || Math.abs(x) < 3 && Math.abs(z) < 3),
                                List.of())
                        .isEmpty(),
                "Scattered large openings in unrelated directions are not one clipped geode");
        builder.set(0, -20, 0, CavitySnapshot.AIR);
        var snapshot = builder.build();
        builder.set(0, -20, 0, CavitySnapshot.WALL);
        require(
                snapshot.at(0, -10, 0) == CavitySnapshot.AIR,
                "Worker snapshot cannot change with its builder");
        var cache = new StructureChunkCache();
        var source = new Object();
        var entry = cache.claim(0, source);
        cache.complete(0, entry, List.of(), snapshot);
        require(cache.claim(0, source).cavity == snapshot, "Shape scans reuse the chunk cache");
        cache.invalidate(0);
        cache.complete(0, entry, List.of(), snapshot);
        require(
                cache.claim(0, source).cavity == null,
                "Stale shape scans cannot restore an invalidated cache");
        System.out.println(
                "Anonymous cavity contour, opening, negative and snapshot checks passed.");
    }

    private static List<CavitySnapshot> world(Air air) {
        var snapshots = new ArrayList<CavitySnapshot>();
        for (int cx = -3; cx <= 0; cx++)
            for (int cz = -1; cz <= 2; cz++) {
                var builder = new CavitySnapshot.Builder(cx, cz);
                for (int y = CavitySnapshot.MIN_Y; y < CavitySnapshot.MAX_Y; y++)
                    for (int z = 0; z < 16; z++)
                        for (int x = 0; x < 16; x++)
                            builder.set(
                                    x,
                                    y,
                                    z,
                                    air.at(cx * 16 + x + 16, y + 24, cz * 16 + z - 16)
                                            ? CavitySnapshot.AIR
                                            : CavitySnapshot.WALL);
                snapshots.add(builder.build());
            }
        return snapshots;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
