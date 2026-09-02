package com.blanoir.moons.client.management.network;

import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;

import java.util.ArrayDeque;

/** Shared bounded queue and replay guard for outgoing FakeLag modes. */
public final class PacketDelayQueue {
    private final int capacity;
    private final ArrayDeque<Packet<?>> packets = new ArrayDeque<>();
    private final ThreadLocal<Boolean> replaying = ThreadLocal.withInitial(() -> false);
    private Connection connection;

    public PacketDelayQueue(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be positive");
        this.capacity = capacity;
    }

    public void observe(Connection connection) { this.connection = connection; }
    public boolean offer(Packet<?> packet) {
        if (packets.size() >= capacity) return false;
        packets.addLast(packet);
        return true;
    }
    public boolean isFull() { return packets.size() >= capacity; }
    public boolean isEmpty() { return packets.isEmpty(); }
    public int size() { return packets.size(); }
    public boolean isReplaying() { return Boolean.TRUE.equals(replaying.get()); }

    public void flush() {
        if (packets.isEmpty()) return;
        if (connection == null || !connection.isConnected()) { packets.clear(); return; }
        replaying.set(true);
        try {
            Packet<?> packet;
            while ((packet = packets.pollFirst()) != null) connection.send(packet);
        } finally {
            replaying.set(false);
        }
    }

    public void clear() { packets.clear(); }
}
