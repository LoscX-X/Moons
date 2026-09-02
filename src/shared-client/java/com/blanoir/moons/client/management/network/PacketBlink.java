package com.blanoir.moons.client.management.network;

import net.minecraft.network.protocol.Packet;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Thread-safe packet-object buffer. Packets are retained by identity and
 * drained in insertion order, so callers can replay the exact original
 * objects instead of reconstructing equivalent packets later.
 */
public final class PacketBlink {
    private final int capacity;
    private final ArrayDeque<Packet<?>> packets = new ArrayDeque<>();

    public PacketBlink(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
    }

    public synchronized boolean offer(Packet<?> packet) {
        if (packet == null || packets.size() >= capacity) {
            return false;
        }
        packets.addLast(packet);
        return true;
    }

    public synchronized boolean hasCapacity() {
        return packets.size() < capacity;
    }

    public synchronized boolean isEmpty() {
        return packets.isEmpty();
    }

    public synchronized int size() {
        return packets.size();
    }

    public synchronized List<Packet<?>> drain() {
        List<Packet<?>> drained = new ArrayList<>(packets);
        packets.clear();
        return drained;
    }

    public synchronized void clear() {
        packets.clear();
    }
}
