package com.blanoir.moons.client.module.impl.world.structure;

import com.blanoir.moons.client.utils.world.ChunkKey;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;

/** Client-thread scan results; invalidation also removes already collected evidence. */
final class StructureScanResults {
    record Snapshot(
            List<StructureEvidence.Marker> markers,
            List<CavitySnapshot> cavities,
            int invalidation) {}

    private final LinkedHashMap<Long, StructureChunkScanner.Result> chunks = new LinkedHashMap<>();
    private final int[] counts = new int[StructureEvidence.Kind.values().length];
    private final HashSet<Long> invalidated = new HashSet<>();
    private int revision, invalidation;

    int revision() {
        return revision;
    }

    boolean accepts(Snapshot snapshot) {
        return snapshot.invalidation == invalidation;
    }

    void invalidate(long key) {
        invalidated.add(key);
        var removed = chunks.remove(key);
        if (removed == null) return;
        for (var marker : removed.markers()) counts[marker.kind().ordinal()]--;
        revision++;
        invalidation++;
    }

    void append(long key, List<StructureEvidence.Marker> markers, CavitySnapshot cavity) {
        if (chunks.containsKey(key)) invalidate(key);
        var retained = new ArrayList<StructureEvidence.Marker>();
        for (var marker : markers) {
            int index = marker.kind().ordinal();
            if (counts[index] >= 12000) continue;
            counts[index]++;
            retained.add(marker);
        }
        chunks.put(key, new StructureChunkScanner.Result(List.copyOf(retained), cavity));
        revision++;
    }

    boolean canRetain(StructureEvidence.Found found) {
        var box = found.bounds();
        for (long key : invalidated) {
            int x = ChunkKey.unpackX(key) * 16, z = ChunkKey.unpackZ(key) * 16;
            if (box.maxX > x && box.minX < x + 16 && box.maxZ > z && box.minZ < z + 16)
                return false;
        }
        return true;
    }

    Snapshot snapshot() {
        var markers = new ArrayList<StructureEvidence.Marker>();
        var cavities = new ArrayList<CavitySnapshot>();
        for (var result : chunks.values()) {
            markers.addAll(result.markers());
            if (result.cavity() != null) cavities.add(result.cavity());
        }
        return new Snapshot(List.copyOf(markers), List.copyOf(cavities), invalidation);
    }
}
