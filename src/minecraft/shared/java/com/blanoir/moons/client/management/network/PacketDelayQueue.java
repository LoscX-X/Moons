package com.blanoir.moons.client.management.network;

import net.minecraft.client.Minecraft;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;

/**
 * Connection-bound outgoing Blink and timed lag. Each consumer owns an instance.
 * Call observe on sends and ticks, and discard on world changes/unload.
 * Replay uses normal sends so rotation and send-completion observers still run.
 */
public final class PacketDelayQueue {
    private final LagUtils<Packet<?>> packets;
    private Connection connection;
    private Object context;
    private long generation;

    public PacketDelayQueue(int capacity) {
        packets = new LagUtils<>(capacity);
    }

    public synchronized void observe(Connection connection) {
        observe(connection, null);
    }

    /** Tick-side observation also clears old-world packets when no send occurs. */
    public synchronized void observeClient(Minecraft client) {
        var listener = client == null ? null : client.getConnection();
        observe(
                listener == null ? null : listener.getConnection(),
                client == null ? null : client.level);
    }

    /** Identity changes discard stale packets; they must never reach another session. */
    public synchronized void observe(Connection connection, Object context) {
        if (this.connection != connection
                || this.context != context
                || connection == null
                || !connection.isConnected()) {
            packets.clear();
            generation++;
        }
        this.connection = connection;
        this.context = context;
    }

    /** Manual Blink: only cancel the original event when this returns true. */
    public synchronized boolean offer(Packet<?> packet) {
        return canCapture() && packets.offer(packet);
    }

    /** Timed lag: call flushDue every tick, including ticks without outgoing packets. */
    public synchronized boolean offer(Packet<?> packet, long delayMillis) {
        return canCapture() && packets.offer(packet, delayMillis);
    }

    private boolean canCapture() {
        return !LagUtils.isReplaying() && connection != null && connection.isConnected();
    }

    public synchronized boolean isFull() {
        return !packets.hasCapacity();
    }

    public synchronized boolean isEmpty() {
        return packets.isEmpty();
    }

    public synchronized int size() {
        return packets.size();
    }

    public synchronized long ageMillis() {
        return packets.age(LagUtils.nowMillis());
    }

    public boolean isReplaying() {
        return LagUtils.isReplaying();
    }

    public void flush() {
        flush(Integer.MAX_VALUE);
    }

    /** Revalidate the current world before flushing from settings/disable callbacks. */
    public synchronized void flushClient(Minecraft client) {
        if (packets.isEmpty()) return;
        observeClient(client);
        flush();
    }

    public synchronized void flush(int count) {
        if (count < 0) throw new IllegalArgumentException("count cannot be negative");
        replay(count, false);
    }

    public synchronized void flushDue() {
        replay(Integer.MAX_VALUE, true);
    }

    private void replay(int count, boolean dueOnly) {
        if (LagUtils.isReplaying()) return;
        Connection observed = connection;
        long observedGeneration = generation;
        long now = LagUtils.nowMillis();
        LagUtils.replay(
                () -> {
                    for (int sent = 0; sent < count; sent++) {
                        // A synchronous observer can discard or replace the context during send.
                        if (observedGeneration != generation) return;
                        if (observed == null || !observed.isConnected()) {
                            discard();
                            return;
                        }
                        Packet<?> packet = dueOnly ? packets.pollDue(now) : packets.poll();
                        if (packet == null) return;
                        observed.send(packet);
                    }
                });
    }

    public synchronized void clear() {
        packets.clear();
        generation++;
    }

    public synchronized void discard() {
        clear();
        connection = null;
        context = null;
    }
}
