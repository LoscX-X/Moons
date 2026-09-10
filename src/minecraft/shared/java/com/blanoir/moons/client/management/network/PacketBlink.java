package com.blanoir.moons.client.management.network;

import net.minecraft.network.protocol.Packet;

import java.util.List;
import java.util.function.Predicate;

/**
 * Thread-safe packet-object buffer. Packets are retained by identity and
 * drained in insertion order, so callers can replay the exact original
 * objects instead of reconstructing equivalent packets later.
 */
public final class PacketBlink {
    private final LagUtils<Packet<?>> packets;

    public PacketBlink(int capacity) {
        packets = new LagUtils<>(capacity);
    }

    public synchronized boolean offer(Packet<?> packet) {
        return packets.offer(packet);
    }

    public synchronized boolean hasCapacity() {
        return packets.hasCapacity();
    }

    public synchronized boolean isEmpty() {
        return packets.isEmpty();
    }

    public synchronized int size() {
        return packets.size();
    }

    public synchronized List<Packet<?>> drain() {
        return packets.drain();
    }

    public synchronized List<Packet<?>> drain(int count) {
        return packets.drain(count);
    }

    /** Release n movement packets without reordering actions between them. */
    public synchronized List<Packet<?>> drainThrough(int count, Predicate<Packet<?>> matches) {
        return packets.drainThrough(count, matches);
    }

    public synchronized List<Packet<?>> snapshot() {
        return packets.snapshot();
    }

    public synchronized long ageMillis() {
        return packets.age(LagUtils.nowMillis());
    }

    public synchronized void clear() {
        packets.clear();
    }
}
