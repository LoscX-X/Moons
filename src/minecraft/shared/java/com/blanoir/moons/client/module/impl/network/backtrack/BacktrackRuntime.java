package com.blanoir.moons.client.module.impl.network.backtrack;

import com.blanoir.moons.client.access.PacketAccess;
import com.blanoir.moons.client.event.frame.FrameEvent;
import com.blanoir.moons.client.event.frame.WorldRenderEvent;
import com.blanoir.moons.client.management.network.TrackedEntityPosition;
import com.blanoir.moons.client.management.targeting.Targeting;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketType;
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundSetHealthPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/** Owns one attacked player and its movement history. All mutable state stays on the client thread. */
public final class BacktrackRuntime {
    private final BacktrackConfig config;
    private final TrackedEntityPosition position = new TrackedEntityPosition();
    private final BacktrackPacketQueue<Snapshot> packets = new BacktrackPacketQueue<>();
    private final BacktrackWindow window = new BacktrackWindow();
    private final BacktrackOverlay overlay;
    private Player target;
    private int tick;

    public BacktrackRuntime(BacktrackConfig config) {
        this.config = config;
        overlay = new BacktrackOverlay(config);
    }

    /** Netty entry point. Vanilla's processor preserves spawn/move/remove ordering. */
    public boolean handleIncomingPacket(Packet<?> packet, PacketListener listener) {
        if (!config.enabled() || !(listener instanceof ClientGamePacketListener gameListener))
            return false;
        // Login creates LocalPlayer, which Minecraft.getConnection() depends on.
        // It must reach vanilla before a replay envelope can validate that connection.
        if (packet instanceof ClientboundLoginPacket) return false;
        if (!isMovement(packet)
                && !(packet instanceof ClientboundRemoveEntitiesPacket)
                && !resetsTracking(packet)) return false;
        Minecraft.getInstance()
                .packetProcessor()
                .scheduleIfPossible(gameListener, new TrackingPacket(this, packet));
        return true;
    }

    public void attack(Minecraft client, Entity entity) {
        if (!config.enabled()) return;
        if (!inGame(client) || !eligible(client, entity) || config.delayMillis() == 0) {
            release();
            return;
        }
        if (entity != target) {
            release();
            if (distanceSquared(client, entity, entity.position()) > maxRangeSquared()) return;
            target = (Player) entity;
            position.setBaseFrom(target);
            window.seed(position.base(), tick);
        }
        // Consecutive attacks extend intent without resampling or postponing queued deadlines.
        window.attack(nowMillis(), tick);
    }

    private boolean receive(Minecraft client, Packet<?> packet) {
        if (resetsTracking(packet)
                || (packet instanceof ClientboundRemoveEntitiesPacket remove
                        && target != null
                        && PacketAccess.removedEntityIds(remove).contains(target.getId()))) {
            release();
            return false;
        }
        if (!validate(client) || !movesTarget(packet, client.level)) return false;

        long now = nowMillis();
        packets.releaseDue(now, this::replay);
        Vec3 previous = position.base();
        Vec3 real = position.handlePacket(packet, client.level, target);
        if (real == null) return false;
        double realDistance = distanceSquared(client, target, real);
        BacktrackWindow.Decision decision =
                window.observe(
                        previous,
                        real,
                        tick,
                        distanceSquared(client, target, previous),
                        realDistance,
                        distanceSquared(client, target, target.position()));
        if (decision == BacktrackWindow.Decision.RESET || realDistance > maxRangeSquared()) {
            release();
            return false;
        }
        if (decision == BacktrackWindow.Decision.RELEASE || !window.ready(now, tick)) {
            packets.releaseAll(this::replay);
            return false;
        }
        if (!packets.offer(
                new Snapshot(packet, client.getConnection(), client.level),
                now,
                config.delayMillis())) {
            release();
            return false;
        }
        return true;
    }

    public void tick(Minecraft client) {
        if (!config.enabled()) return;
        tick++;
        advance(client);
    }

    public void frame(FrameEvent event) {
        if (!config.enabled() || !event.client().isSameThread()) return;
        advance(event.client());
        overlay.frame(visibleTarget(), position.base(), event.deltaSeconds());
    }

    private void advance(Minecraft client) {
        if (!validate(client)) return;
        if (!window.ready(nowMillis(), tick)
                || !BacktrackWindow.useful(
                        distanceSquared(client, target, position.base()),
                        distanceSquared(client, target, target.position()))) {
            packets.releaseAll(this::replay);
        } else {
            packets.releaseDue(nowMillis(), this::replay);
        }
    }

    private boolean validate(Minecraft client) {
        if (!inGame(client)) {
            discard();
            return false;
        }
        if (target == null) return false;
        // Entity identity matters: IDs can be reused after a dimension/context change.
        if (client.level.getEntity(target.getId()) != target) {
            discard();
            return false;
        }
        if (!eligible(client, target)
                || config.delayMillis() == 0
                || window.expired(nowMillis(), tick)
                || distanceSquared(client, target, position.base()) > maxRangeSquared()) {
            release();
            return false;
        }
        return true;
    }

    public void release() {
        packets.releaseAll(this::replay);
        resetTarget();
    }

    public void discard() {
        packets.clear();
        resetTarget();
    }

    private void resetTarget() {
        target = null;
        position.base(Vec3.ZERO);
        window.reset();
        overlay.reset();
    }

    public boolean isLagging() {
        return !packets.isEmpty();
    }

    public String hudStats() {
        return packets.isEmpty() ? "Ready" : packets.age(nowMillis()) + "ms";
    }

    public void renderEsp(WorldRenderEvent event) {
        overlay.renderEsp(event, visibleTarget(), position.base());
    }

    public void renderModel(
            PoseStack poses, LevelRenderState state, SubmitNodeCollector collector) {
        overlay.renderModel(poses, state, collector, visibleTarget(), position.base());
    }

    private Player visibleTarget() {
        return config.enabled() && target != null && target.isAlive() && !packets.isEmpty()
                ? target
                : null;
    }

    private void replay(Snapshot snapshot) {
        Minecraft client = Minecraft.getInstance();
        if (client != null
                && client.getConnection() == snapshot.listener()
                && client.level == snapshot.level()) {
            apply(snapshot.packet(), snapshot.listener());
        }
    }

    @SuppressWarnings("unchecked")
    private static void apply(Packet<?> packet, ClientGamePacketListener listener) {
        ((Packet<ClientGamePacketListener>) packet).handle(listener);
    }

    private boolean movesTarget(Packet<?> packet, ClientLevel level) {
        if (packet instanceof ClientboundMoveEntityPacket move)
            return move.getEntity(level) == target;
        if (packet instanceof ClientboundTeleportEntityPacket teleport)
            return teleport.id() == target.getId();
        if (packet instanceof ClientboundEntityPositionSyncPacket sync)
            return sync.id() == target.getId();
        return false;
    }

    private static boolean isMovement(Packet<?> packet) {
        return packet instanceof ClientboundMoveEntityPacket
                || packet instanceof ClientboundTeleportEntityPacket
                || packet instanceof ClientboundEntityPositionSyncPacket;
    }

    private static boolean resetsTracking(Packet<?> packet) {
        return packet instanceof ClientboundPlayerPositionPacket
                || packet instanceof ClientboundDisconnectPacket
                || packet instanceof ClientboundRespawnPacket
                || (packet instanceof ClientboundSetHealthPacket health && health.getHealth() <= 0);
    }

    private static boolean eligible(Minecraft client, Entity entity) {
        return entity instanceof Player player
                && player.isAlive()
                && !player.isSleeping()
                && client.player.tickCount > 10
                && !player.hasPassenger(client.player)
                && Targeting.isEnemyPlayer(client, player);
    }

    private static boolean inGame(Minecraft client) {
        return client != null && client.player != null && client.level != null;
    }

    private double maxRangeSquared() {
        return config.maxRange() * config.maxRange();
    }

    private static double distanceSquared(Minecraft client, Entity entity, Vec3 at) {
        return entity.getBoundingBox()
                .inflate(entity.getPickRadius())
                .move(at.subtract(entity.position()))
                .distanceToSqr(client.player.getEyePosition());
    }

    private static long nowMillis() {
        return System.nanoTime() / 1_000_000L;
    }

    private record Snapshot(Packet<?> packet, ClientPacketListener listener, ClientLevel level) {}

    /** Local processor envelope; never serialized or sent to the server. */
    private record TrackingPacket(BacktrackRuntime runtime, Packet<?> original)
            implements Packet<ClientGamePacketListener> {
        @Override
        @SuppressWarnings({"rawtypes", "unchecked"})
        public PacketType<? extends Packet<ClientGamePacketListener>> type() {
            return (PacketType) original.type();
        }

        @Override
        public void handle(ClientGamePacketListener listener) {
            Minecraft client = Minecraft.getInstance();
            if (client.getConnection() != listener) return;
            if (!runtime.config.enabled() || !runtime.receive(client, original))
                apply(original, listener);
        }

        @Override
        public boolean isSkippable() {
            return original.isSkippable();
        }

        @Override
        public boolean isTerminal() {
            return original.isTerminal();
        }
    }
}
