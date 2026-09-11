package com.blanoir.moons.client.management.rotation;

import com.blanoir.moons.client.event.EventBus;
import com.blanoir.moons.client.event.EventPriority;
import com.blanoir.moons.client.event.network.PacketSendEvent;
import com.blanoir.moons.client.management.lease.RotationLease;
import com.blanoir.moons.client.utils.rotation.Rotation;

import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;

import java.util.Map;
import java.util.WeakHashMap;

/** Owns shared rotation decisions, connection-scoped send records and packet confirmation. */
public final class RotationManager {
    public record Sent(boolean valid, float yaw, float pitch, int tick, long sequence) {
        private static Sent empty() {
            return new Sent(false, 0, 0, Integer.MIN_VALUE, 0);
        }

        public Rotation rotation() {
            return new Rotation(yaw, pitch);
        }
    }

    private static Sent latest = Sent.empty();

    private record Pending(RotationLease.Submission submission) {}

    private static final Map<Packet<?>, Pending> pending = new WeakHashMap<>();
    private static Packet<?> lastPacket;
    private static RotationLease.Submission lastSubmission;
    private static Object context;
    private static Object player;
    private static Object level;
    private static boolean initialized;

    private RotationManager() {}

    public static void init() {
        if (initialized) return;
        initialized = true;
        EventBus.CLIENT_CONTEXT_CHANGED.register(
                "RotationManager.context", EventPriority.HIGHEST, event -> reset());
        EventBus.PACKET_SEND_PRE.register(
                "RotationManager.capture", EventPriority.HIGHEST, RotationManager::capture);
        EventBus.PACKET_SEND_POST.register(
                "RotationManager.sent", EventPriority.HIGHEST, RotationManager::record);
    }

    public static synchronized Sent latest() {
        return latest;
    }

    /** An unknown history uses the camera only as an initial seed, never as a sent claim. */
    public static synchronized Rotation start(Rotation camera) {
        return latest.valid() ? latest.rotation() : camera;
    }

    public static Rotation start(Minecraft client) {
        var currentPlayer = client == null ? null : client.player;
        synchronizeContext(client);
        return start(
                client == null || currentPlayer == null
                        ? new Rotation(0, 0)
                        : new Rotation(currentPlayer.getYRot(), currentPlayer.getXRot()));
    }

    static synchronized void capture(PacketSendEvent.Pre event) {
        if (!(event.packet() instanceof ServerboundMovePlayerPacket)) return;
        RotationLease.Submission submission = RotationLease.submission();
        // Preserve the original owner when a cancelled packet is queued and replayed.
        pending.putIfAbsent(event.packet(), new Pending(submission));
    }

    private static void record(PacketSendEvent.Post event) {
        if (!(event.packet() instanceof ServerboundMovePlayerPacket movement)) return;
        Minecraft client = Minecraft.getInstance();
        var currentPlayer = client == null ? null : client.player;
        if (client == null
                || currentPlayer == null
                || event.connection() != currentPlayer.connection.getConnection()) return;
        synchronized (RotationManager.class) {
            synchronizeContext(client);
            record(movement, currentPlayer.tickCount);
        }
    }

    static synchronized void record(ServerboundMovePlayerPacket movement, int tick) {
        lastPacket = movement;
        Pending original = pending.remove(movement);
        lastSubmission = original == null ? null : original.submission();
        // Position-only packets carry the existing direction, not the camera's current direction.
        if (!movement.hasRotation() && !latest.valid()) return;
        latest =
                new Sent(
                        true,
                        movement.getYRot(latest.yaw()),
                        movement.getXRot(latest.pitch()),
                        tick,
                        latest.sequence() + 1);
        RotationLease.confirmManual(latest.rotation());
        if (lastSubmission != null && sentFor(lastSubmission.lease(), movement)) {
            lastSubmission.lease().confirm(latest.yaw(), latest.pitch());
        }
    }

    public static synchronized boolean sentFor(RotationLease lease, Packet<?> packet) {
        return latest.valid()
                && lastPacket == packet
                && lastSubmission != null
                && lastSubmission.lease() == lease
                && lease.matches(lastSubmission)
                && same(lastSubmission.rotation(), latest.rotation());
    }

    public static synchronized boolean observed(Packet<?> packet) {
        return lastPacket == packet && latest.valid();
    }

    private static synchronized void synchronizeContext(Minecraft client) {
        if (client == null) return;
        Object current = client.getConnection();
        if ((context != null || player != null || level != null)
                && (context != current || player != client.player || level != client.level))
            reset();
        context = current;
        player = client.player;
        level = client.level;
    }

    public static boolean same(Rotation first, Rotation second) {
        return Float.floatToIntBits(first.yaw()) == Float.floatToIntBits(second.yaw())
                && Float.floatToIntBits(first.pitch()) == Float.floatToIntBits(second.pitch());
    }

    public static synchronized void reset() {
        latest = Sent.empty();
        pending.clear();
        lastPacket = null;
        lastSubmission = null;
        context = null;
        player = level = null;
        RotationLease.resetAll();
        MoveFix.reset();
    }

    /** A result lasts through one action/movement window, not until a packet is confirmed. */
    public record Decision(String owner, Rotation rotation, boolean correctMovement, long window) {
        public boolean valid() {
            return window == RotationLease.window();
        }
    }

    public static Decision resolve() {
        return resolve(false);
    }

    /** Do not commit a packet candidate early when the owner opted out of movement correction. */
    public static Decision forMovement() {
        return resolve(true);
    }

    private static Decision resolve(boolean forMovement) {
        synchronized (RotationManager.class) {
            Rotation manual = RotationLease.manualRotation();
            if (manual != null) {
                return new Decision("ManualUse", manual, true, RotationLease.window());
            }
            RotationLease.Submission output = RotationLease.resolveSubmission(forMovement);
            return output == null
                    ? null
                    : new Decision(
                            output.lease().owner(),
                            output.rotation(),
                            output.correctMovement(),
                            RotationLease.window());
        }
    }
}
