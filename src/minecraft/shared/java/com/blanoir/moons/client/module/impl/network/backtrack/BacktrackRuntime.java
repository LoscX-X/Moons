package com.blanoir.moons.client.module.impl.network.backtrack;

import com.blanoir.moons.client.access.PacketAccess;
import com.blanoir.moons.client.chat.ClientChat;
import com.blanoir.moons.client.event.frame.FrameEvent;
import com.blanoir.moons.client.event.frame.WorldRenderEvent;
import com.blanoir.moons.client.management.network.LagUtils;
import com.blanoir.moons.client.management.network.TrackedEntityPosition;
import com.blanoir.moons.client.utils.math.RandomMath;
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
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/** Owns the selected target and history for Attack, Range and Intent modes on the client thread. */
public final class BacktrackRuntime {
    private final BacktrackConfig config;
    private final TrackedEntityPosition position = new TrackedEntityPosition();
    private final BacktrackPacketQueue<Snapshot> packets = new BacktrackPacketQueue<>(1024);
    private final BacktrackOverlay overlay;
    private LivingEntity target;
    private int tick;
    private long attackedAt = -1;
    private long blockedUntil;
    private long lastInRange;
    private int baseDelay;
    private int currentDelay;

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
        if (!inGame(client)
                || !BacktrackTargets.eligible(client, entity)
                || config.delayMillis() == 0) {
            release();
            return;
        }
        attackedAt = nowMillis();
        if (config.targetMode() != BacktrackConfig.TargetMode.ATTACK) return;
        select(client, (LivingEntity) entity);
    }

    private void select(Minecraft client, LivingEntity entity) {
        if (entity == null) {
            packets.drain(nowMillis());
            return;
        }
        long now = nowMillis();
        if (now < blockedUntil) return;
        // An expired attack window may drain existing history, but cannot create new history.
        if (target == null
                && !config.targetMode()
                        .acceptsAttackAge(
                                attackedAt < 0 ? -1 : now - attackedAt, config.lastAttackMillis()))
            return;
        double distance = distanceSquared(client, entity, entity.position());
        if (distance < config.minRange() * config.minRange() || distance > maxRangeSquared())
            return;
        if (entity != target) {
            if (target != null) {
                packets.drain(now);
                return;
            }
            if (!RandomMath.chancePercent(config.chance())) {
                release();
                blockedUntil = now + RandomMath.betweenInclusive(100, 150);
                return;
            }
            target = entity;
            position.setBaseFrom(target);
            baseDelay = RandomMath.betweenInclusive(config.minDelayMillis(), config.delayMillis());
            currentDelay = sessionDelay(client);
            packets.start(currentDelay);
        }
        lastInRange = now;
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

        // Relative teleport flags are interpreted by vanilla after prior deltas replay.
        if (packet instanceof ClientboundTeleportEntityPacket) {
            release();
            return false;
        }
        long now = nowMillis();
        packets.releaseDue(now, this::replay);
        Vec3 previous = position.base();
        Vec3 real = position.handlePacket(packet, client.level, target);
        if (real == null) return false;
        // Discontinuous teleports end history. Direction changes never restart its delay.
        if (previous.distanceToSqr(real) > 25.0) {
            release();
            return false;
        }
        if (distanceSquared(client, target, real) > maxRangeSquared()) packets.drain(now);
        if (packets.size() >= config.queueLimit()
                || !packets.offer(
                        new Snapshot(packet, client.getConnection(), client.level), now)) {
            release();
            return false;
        }
        packets.releaseDue(now, this::replay);
        return true;
    }

    public void tick(Minecraft client) {
        if (!config.enabled()) return;
        tick++;
        if (!inGame(client)) {
            discard();
            return;
        }
        if (target == null && config.targetMode() != BacktrackConfig.TargetMode.ATTACK)
            select(client, BacktrackTargets.find(client, config));
        if (target != null
                && config.targetMode() == BacktrackConfig.TargetMode.INTENT
                && !BacktrackTargets.intended(client, target, config.maxRange()))
            packets.drain(nowMillis());
        advance(client);
        if (config.actionBar() && tick % 4 == 0)
            ClientChat.actionBar(client, "Backtrack " + hudStats());
    }

    public void frame(FrameEvent event) {
        if (!config.enabled() || !event.client().isSameThread()) return;
        advance(event.client());
        overlay.frame(visibleTarget(), position.base(), event.deltaSeconds());
    }

    private void advance(Minecraft client) {
        if (!validate(client)) return;
        long now = nowMillis();
        packets.releaseDue(now, this::replay);
        if (packets.drained(now)) finishTarget(now);
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
        long now = nowMillis();
        double visibleDistance = distanceSquared(client, target, target.position());
        boolean inRange =
                visibleDistance >= config.minRange() * config.minRange()
                        && visibleDistance <= maxRangeSquared();
        if (inRange) lastInRange = now;
        if (!BacktrackTargets.eligible(client, target)
                || client.player.tickCount <= 10
                || config.delayMillis() == 0
                || !config.targetMode()
                        .acceptsAttackAge(
                                attackedAt < 0 ? -1 : now - attackedAt, config.lastAttackMillis())
                || visibleDistance < config.minRange() * config.minRange()
                || !inRange && now - lastInRange > config.trackingBufferMillis()
                || config.pauseOnHurt() && target.hurtTime >= config.hurtTime()
                || distanceSquared(client, target, position.base()) > maxRangeSquared()) {
            packets.drain(now);
        }
        return true;
    }

    public void release() {
        packets.releaseAll(this::replay);
        finishTarget(nowMillis());
    }

    private void finishTarget(long now) {
        if (target != null)
            blockedUntil =
                    now + RandomMath.betweenInclusive(config.nextDelayMin(), config.nextDelayMax());
        resetTarget();
    }

    public void discard() {
        packets.clear();
        resetTarget();
        attackedAt = -1;
        blockedUntil = 0;
    }

    private void resetTarget() {
        target = null;
        position.base(Vec3.ZERO);
        overlay.reset();
    }

    public boolean isLagging() {
        return !packets.isEmpty();
    }

    public String hudStats() {
        return config.minDelayMillis() + "–" + config.delayMillis() + " ms";
    }

    private int sessionDelay(Minecraft client) {
        int maximum = config.delayMillis();
        if (config.pingRatio() > 0 && client.getConnection() != null) {
            var info = client.getConnection().getPlayerInfo(client.player.getUUID());
            if (info != null && info.getLatency() > 0)
                maximum =
                        Math.clamp(
                                (int) Math.round(info.getLatency() * config.pingRatio()),
                                config.minDelayMillis(),
                                maximum);
        }
        return (int) Math.round(Math.clamp(baseDelay, config.minDelayMillis(), maximum));
    }

    public void renderEsp(WorldRenderEvent event) {
        overlay.renderEsp(event, visibleTarget(), position.base());
    }

    public void renderModel(
            PoseStack poses, LevelRenderState state, SubmitNodeCollector collector) {
        overlay.renderModel(poses, state, collector, visibleTarget(), position.base());
    }

    private LivingEntity visibleTarget() {
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
        return LagUtils.nowMillis();
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
