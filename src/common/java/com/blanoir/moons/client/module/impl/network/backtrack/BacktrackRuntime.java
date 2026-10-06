package com.blanoir.moons.client.module.impl.network.backtrack;

import com.blanoir.moons.client.access.PacketAccess;
import com.blanoir.moons.client.chat.ClientChat;
import com.blanoir.moons.client.compat.math.VecMath;
import com.blanoir.moons.client.event.frame.FrameEvent;
import com.blanoir.moons.client.event.frame.WorldRenderEvent;
import com.blanoir.moons.client.manager.network.DelayedValueQueue;
import com.blanoir.moons.client.manager.network.TrackedEntityPosition;
import com.blanoir.moons.client.utils.math.RandomMath;
import com.blanoir.moons.client.utils.world.LegacyWorld;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.network.INetHandler;
import net.minecraft.network.Packet;
import net.minecraft.network.play.INetHandlerPlayClient;
import net.minecraft.network.play.server.S01PacketJoinGame;
import net.minecraft.network.play.server.S06PacketUpdateHealth;
import net.minecraft.network.play.server.S07PacketRespawn;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;
import net.minecraft.network.play.server.S13PacketDestroyEntities;
import net.minecraft.network.play.server.S14PacketEntity;
import net.minecraft.network.play.server.S18PacketEntityTeleport;
import net.minecraft.network.play.server.S40PacketDisconnect;
import net.minecraft.util.Vec3;

/** Owns the selected target and history for Attack, Range and Intent modes on the client thread. */
public final class BacktrackRuntime {
    private final BacktrackConfig config;
    private final TrackedEntityPosition position = new TrackedEntityPosition();
    private final BacktrackPacketQueue<Snapshot> packets = new BacktrackPacketQueue<>(1024);
    private final BacktrackOverlay overlay;
    private EntityLivingBase target;
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
    public boolean handleIncomingPacket(Packet<?> packet, INetHandler listener) {
        if (!config.enabled() || !(listener instanceof INetHandlerPlayClient gameListener))
            return false;
        // Login creates LocalPlayer, which Minecraft.getNetHandler() depends on.
        // It must reach vanilla before a replay envelope can validate that connection.
        if (packet instanceof S01PacketJoinGame) return false;
        if (!isMovement(packet)
                && !(packet instanceof S13PacketDestroyEntities)
                && !resetsTracking(packet)) return false;
        Minecraft client = Minecraft.getMinecraft();
        client.addScheduledTask(
                () -> {
                    if (client.getNetHandler() != gameListener) return;
                    if (!config.enabled() || !receive(client, packet)) apply(packet, gameListener);
                });
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
        select(client, (EntityLivingBase) entity);
    }

    private void select(Minecraft client, EntityLivingBase entity) {
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
        double distance = distanceSquared(client, entity, VecMath.position(entity));
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
                || (packet instanceof S13PacketDestroyEntities remove
                        && target != null
                        && PacketAccess.removedEntityIds(remove).contains(target.getEntityId()))) {
            release();
            return false;
        }
        if (!validate(client) || !movesTarget(packet, client.theWorld)) return false;

        // Relative teleport flags are interpreted by vanilla after prior deltas replay.
        if (packet instanceof S18PacketEntityTeleport) {
            release();
            return false;
        }
        long now = nowMillis();
        packets.releaseDue(now, this::replay);
        Vec3 previous = position.base();
        Vec3 real = position.handlePacket(packet, client.theWorld, target);
        if (real == null) return false;
        // Discontinuous teleports end history. Direction changes never restart its delay.
        if (previous.squareDistanceTo(real) > 25.0) {
            release();
            return false;
        }
        if (distanceSquared(client, target, real) > maxRangeSquared()) packets.drain(now);
        if (packets.size() >= config.queueLimit()
                || !packets.offer(
                        new Snapshot(packet, client.getNetHandler(), client.theWorld), now)) {
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
        if (!config.enabled() || !event.client().isCallingFromMinecraftThread()) return;
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
        if (client.theWorld.getEntityByID(target.getEntityId()) != target) {
            discard();
            return false;
        }
        long now = nowMillis();
        double visibleDistance = distanceSquared(client, target, VecMath.position(target));
        boolean inRange =
                visibleDistance >= config.minRange() * config.minRange()
                        && visibleDistance <= maxRangeSquared();
        if (inRange) lastInRange = now;
        if (!BacktrackTargets.eligible(client, target)
                || client.thePlayer.ticksExisted <= 10
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
        position.base(VecMath.ZERO);
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
        if (config.pingRatio() > 0 && client.getNetHandler() != null) {
            var info = client.getNetHandler().getPlayerInfo(client.thePlayer.getUniqueID());
            if (info != null && info.getResponseTime() > 0)
                maximum =
                        Math.clamp(
                                (int) Math.round(info.getResponseTime() * config.pingRatio()),
                                config.minDelayMillis(),
                                maximum);
        }
        return (int) Math.round(Math.clamp(baseDelay, config.minDelayMillis(), maximum));
    }

    public void renderEsp(WorldRenderEvent event) {
        overlay.renderEsp(event, visibleTarget(), position.base());
    }

    public void renderModel(float partialTicks) {
        overlay.renderModel(partialTicks, visibleTarget(), position.base());
    }

    private EntityLivingBase visibleTarget() {
        return config.enabled() && target != null && target.isEntityAlive() && !packets.isEmpty()
                ? target
                : null;
    }

    private void replay(Snapshot snapshot) {
        Minecraft client = Minecraft.getMinecraft();
        if (client != null
                && client.getNetHandler() == snapshot.listener()
                && client.theWorld == snapshot.level()) {
            apply(snapshot.packet(), snapshot.listener());
        }
    }

    @SuppressWarnings("unchecked")
    private static void apply(Packet<?> packet, INetHandlerPlayClient listener) {
        ((Packet<INetHandlerPlayClient>) packet).processPacket(listener);
    }

    private boolean movesTarget(Packet<?> packet, WorldClient level) {
        if (packet instanceof S14PacketEntity move) return move.getEntity(level) == target;
        if (packet instanceof S18PacketEntityTeleport teleport)
            return teleport.getEntityId() == target.getEntityId();
        return false;
    }

    private static boolean isMovement(Packet<?> packet) {
        return packet instanceof S14PacketEntity || packet instanceof S18PacketEntityTeleport;
    }

    private static boolean resetsTracking(Packet<?> packet) {
        return packet instanceof S08PacketPlayerPosLook
                || packet instanceof S40PacketDisconnect
                || packet instanceof S07PacketRespawn
                || (packet instanceof S06PacketUpdateHealth health && health.getHealth() <= 0);
    }

    private static boolean inGame(Minecraft client) {
        return client != null && client.thePlayer != null && client.theWorld != null;
    }

    private double maxRangeSquared() {
        return config.maxRange() * config.maxRange();
    }

    private static double distanceSquared(Minecraft client, Entity entity, Vec3 at) {
        return LegacyWorld.distanceSquared(
                LegacyWorld.move(
                        LegacyWorld.inflate(
                                entity.getEntityBoundingBox(), entity.getCollisionBorderSize()),
                        at.subtract(VecMath.position(entity))),
                client.thePlayer.getPositionEyes(1.0F));
    }

    private static long nowMillis() {
        return DelayedValueQueue.nowMillis();
    }

    private record Snapshot(Packet<?> packet, NetHandlerPlayClient listener, WorldClient level) {}
}
