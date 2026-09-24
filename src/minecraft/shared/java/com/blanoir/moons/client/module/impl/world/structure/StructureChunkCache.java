package com.blanoir.moons.client.module.impl.world.structure;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.LongPredicate;

/** Client-thread cache, bounded by both chunk count and retained marker count. */
final class StructureChunkCache {
    static final int MAX_CHUNKS = 1024, MAX_MARKERS = 65_536;
    private final LinkedHashMap<Long, Entry> chunks = new LinkedHashMap<>(128, .75f, true);
    private int markerCount;

    static final class Entry {
        final Object source;
        List<StructureEvidence.Marker> markers;
        CavitySnapshot cavity;

        Entry(Object source) {
            this.source = source;
        }
    }

    Entry claim(long key, Object source) {
        Entry entry = chunks.get(key);
        if (entry != null && entry.source == source) return entry;
        invalidate(key);
        entry = new Entry(source);
        chunks.put(key, entry);
        trim();
        return entry;
    }

    void complete(long key, Entry entry, List<StructureEvidence.Marker> markers) {
        complete(key, entry, markers, null);
    }

    void complete(
            long key, Entry entry, List<StructureEvidence.Marker> markers, CavitySnapshot cavity) {
        // A block/chunk packet may have invalidated this entry midway through the bounded scan.
        if (chunks.get(key) != entry) return;
        if (entry.markers != null) markerCount -= entry.markers.size();
        entry.markers = List.copyOf(markers);
        entry.cavity = cavity;
        markerCount += entry.markers.size();
        trim();
    }

    void invalidate(long key) {
        Entry removed = chunks.remove(key);
        if (removed != null && removed.markers != null) markerCount -= removed.markers.size();
    }

    boolean isCurrent(long key, Entry entry) {
        return chunks.get(key) == entry;
    }

    void retain(LongPredicate keep) {
        var iterator = chunks.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (keep.test(entry.getKey())) continue;
            if (entry.getValue().markers != null) markerCount -= entry.getValue().markers.size();
            iterator.remove();
        }
    }

    private void trim() {
        var iterator = chunks.entrySet().iterator();
        while ((chunks.size() > MAX_CHUNKS || markerCount > MAX_MARKERS) && iterator.hasNext()) {
            Entry entry = iterator.next().getValue();
            if (entry.markers != null) markerCount -= entry.markers.size();
            iterator.remove();
        }
    }

    void clear() {
        chunks.clear();
        markerCount = 0;
    }
}
