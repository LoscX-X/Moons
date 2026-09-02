package com.blanoir.moons.client.management.network;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.protocol.Packet;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.UUID;

/**
 * Reusable single-entity incoming lag buffer. The logical entity is held at
 * its begin-time box while movement packets advance a separate real/server
 * position that renderers and diagnostics can consume.
 */
public final class EntityLag {
    private static final long DEFAULT_INTERPOLATION_NANOS = 50_000_000L;
    private static final long MIN_INTERPOLATION_NANOS = 15_000_000L;
    private static final long MAX_INTERPOLATION_NANOS = 100_000_000L;

    private final Object lock = new Object();
    private final PacketBlink packets;
    private final TrackedEntityPosition realPosition = new TrackedEntityPosition();

    private volatile int targetId = -1;
    private volatile UUID targetUuid;
    private volatile Vec3 interpolationStartPosition;
    private volatile Vec3 serverPosition;
    private volatile long interpolationStartedNanos;
    private volatile long interpolationDurationNanos = DEFAULT_INTERPOLATION_NANOS;
    private volatile long lastServerPacketNanos;
    private AABB laggedBox;
    private Vec3 laggedPosition;

    public EntityLag(int packetCapacity) {
        packets = new PacketBlink(packetCapacity);
    }

    /** Begin lagging a target. Call {@link #stop()} before replacing an active target. */
    public void begin(Entity target) {
        if (target == null) {
            throw new IllegalArgumentException("target cannot be null");
        }
        synchronized (lock) {
            packets.clear();
            targetId = target.getId();
            targetUuid = target.getUUID();
            laggedBox = target.getBoundingBox();
            laggedPosition = target.position();
            realPosition.setBaseFrom(target);
            interpolationStartPosition = target.position();
            serverPosition = target.position();
            interpolationStartedNanos = System.nanoTime();
            interpolationDurationNanos = 1L;
            lastServerPacketNanos = 0L;
        }
    }

    /** Returns true when this exact target movement packet was captured. */
    public boolean capture(Packet<?> packet, ClientLevel level, Entity target) {
        if (packet == null || level == null || target == null || !matches(target)) {
            return false;
        }
        synchronized (lock) {
            if (!matches(target) || !packets.hasCapacity()) {
                return false;
            }
            Vec3 decoded = realPosition.handlePacket(packet, level, target);
            if (decoded == null || !packets.offer(packet)) {
                return false;
            }
            long now = System.nanoTime();
            Vec3 visualPosition = interpolatedPosition(now);
            long packetInterval = lastServerPacketNanos == 0L
                    ? DEFAULT_INTERPOLATION_NANOS
                    : now - lastServerPacketNanos;
            interpolationStartPosition = visualPosition == null ? decoded : visualPosition;
            serverPosition = decoded;
            interpolationStartedNanos = now;
            interpolationDurationNanos = Math.max(
                    MIN_INTERPOLATION_NANOS,
                    Math.min(MAX_INTERPOLATION_NANOS, packetInterval));
            lastServerPacketNanos = now;
            return true;
        }
    }

    public boolean active() {
        return targetId != -1;
    }

    public int targetId() {
        return targetId;
    }

    public Entity target(ClientLevel level) {
        if (level == null || targetId == -1) {
            return null;
        }
        Entity entity = level.getEntity(targetId);
        return matches(entity) ? entity : null;
    }

    public boolean matches(Entity entity) {
        UUID uuid = targetUuid;
        return entity != null && entity.getId() == targetId
                && uuid != null && uuid.equals(entity.getUUID());
    }

    public Vec3 serverPosition() {
        return serverPosition;
    }

    /**
     * Visual-only interpolation between decoded server positions. A monotonic
     * packet-time clock avoids jumping backwards when Minecraft's partial tick
     * resets before another movement packet arrives.
     * Combat checks deliberately continue to use {@link #serverPosition()}.
     */
    public Vec3 renderPosition() {
        return interpolatedPosition(System.nanoTime());
    }

    private Vec3 interpolatedPosition(long now) {
        Vec3 current = serverPosition;
        Vec3 previous = interpolationStartPosition;
        if (current == null) {
            return null;
        }
        if (previous == null) {
            return current;
        }
        long duration = Math.max(1L, interpolationDurationNanos);
        double alpha = Math.max(0.0D, Math.min(
                1.0D, (double) (now - interpolationStartedNanos) / duration));
        return previous.lerp(current, alpha);
    }

    public AABB laggedBox() {
        synchronized (lock) {
            return laggedBox;
        }
    }

    public AABB realBox() {
        synchronized (lock) {
            if (laggedBox == null || laggedPosition == null || serverPosition == null) {
                return null;
            }
            return laggedBox.move(serverPosition.subtract(laggedPosition));
        }
    }

    /** Stops lagging and returns captured packets in their original order. */
    public List<Packet<?>> stop() {
        synchronized (lock) {
            targetId = -1;
            targetUuid = null;
            interpolationStartPosition = null;
            serverPosition = null;
            interpolationStartedNanos = 0L;
            interpolationDurationNanos = DEFAULT_INTERPOLATION_NANOS;
            lastServerPacketNanos = 0L;
            laggedBox = null;
            laggedPosition = null;
            realPosition.base(Vec3.ZERO);
            return packets.drain();
        }
    }

    /** Stops lagging without replaying captured packets. */
    public void discard() {
        synchronized (lock) {
            targetId = -1;
            targetUuid = null;
            interpolationStartPosition = null;
            serverPosition = null;
            interpolationStartedNanos = 0L;
            interpolationDurationNanos = DEFAULT_INTERPOLATION_NANOS;
            lastServerPacketNanos = 0L;
            laggedBox = null;
            laggedPosition = null;
            realPosition.base(Vec3.ZERO);
            packets.clear();
        }
    }

    public int queuedPackets() {
        return packets.size();
    }
}
