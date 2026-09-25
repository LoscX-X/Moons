package com.blanoir.moons.client.module.impl.combat;

import com.blanoir.moons.client.event.EventBus;
import com.blanoir.moons.client.event.EventPriority;
import com.blanoir.moons.client.event.network.PacketReceiveEvent;
import com.blanoir.moons.client.event.network.PacketSendEvent;
import com.blanoir.moons.client.management.network.LagPacketPolicy;
import com.blanoir.moons.client.management.network.LagUtils;
import com.blanoir.moons.client.management.network.PacketDelayQueue;
import com.blanoir.moons.client.utils.client.ClientReady;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.item.Item;

/** Per-packet movement delay during Lunge, followed by gradual FIFO catch-up. */
final class AutoSpearFakeLag {
    private static final PacketDelayQueue PACKETS = new PacketDelayQueue(32);
    private static LocalPlayer player;
    private static ClientLevel level;
    private static Item spear;
    private static int slot;
    private static final Timing TIMING = new Timing();

    private AutoSpearFakeLag() {}

    static void init() {
        EventBus.TICK.register("AutoSpear.fakeLagTick", event -> tick(event.client()));
        EventBus.CLIENT_CONTEXT_CHANGED.register("AutoSpear.fakeLagContext", event -> discard());
    }

    static void initPacketListeners() {
        EventBus.PACKET_SEND_PRE.register(
                "AutoSpear.fakeLagSend", EventPriority.HIGH, AutoSpearFakeLag::send);
        EventBus.PACKET_RECEIVE_APPLY.register("AutoSpear.fakeLagReceive", AutoSpearFakeLag::receive);
    }

    static synchronized void start(Minecraft client) {
        flush(client);
        if (!AutoSpear.isEnabled() || !AutoSpear.fakeLagEnabled()
                || AutoSpearImpact.active() || !ClientReady.aliveGameplay(client))
            return;
        PACKETS.observeClient(client);
        player = client.player;
        level = client.level;
        spear = player.getMainHandItem().getItem();
        slot = player.getInventory().getSelectedSlot();
        TIMING.start(LagUtils.nowMillis(), AutoSpear.fakeLagDelayMs(), AutoSpear.fakeLagDurationMs());
    }

    private static boolean sameContext(Minecraft client) {
        return player != null && ClientReady.world(client)
                && client.player == player && client.level == level;
    }

    private static boolean ready(Minecraft client) {
        return sameContext(client)
                && AutoSpear.isEnabled()
                && AutoSpear.fakeLagEnabled()
                && ClientReady.aliveGameplay(client)
                && client.isWindowActive()
                && !client.isPaused()
                && !player.isSpectator()
                && player.getInventory().getSelectedSlot() == slot
                && player.getMainHandItem().is(spear);
    }

    private static synchronized void tick(Minecraft client) {
        if (player == null) return;
        if (!sameContext(client)) {
            discard();
        } else if (!ready(client)) {
            flush(client);
        } else {
            PACKETS.observeClient(client);
            PACKETS.flushDue();
            if (!TIMING.active(LagUtils.nowMillis()) && PACKETS.isEmpty()) discard();
        }
    }

    private static synchronized void send(PacketSendEvent.Pre event) {
        if (player == null || LagUtils.isReplaying()) return;
        Minecraft client = Minecraft.getInstance();
        if (!sameContext(client) || client.getConnection() == null
                || event.connection() != client.getConnection().getConnection()) {
            discard();
            return;
        }
        PACKETS.observe(event.connection(), client.level);
        if (!ready(client) || PACKETS.isFull()
                || LagPacketPolicy.mustFlushBefore(event.packet())) {
            // Actions, especially the next STAB, follow all earlier positions/rotations.
            flush(client);
            return;
        }
        PACKETS.flushDue();
        if (!sameContext(client)) return;
        long now = LagUtils.nowMillis();
        if (!TIMING.active(now) && PACKETS.isEmpty()) {
            discard();
            return;
        }
        if (event.packet() instanceof ServerboundMovePlayerPacket) {
            // New movement stays behind the tail while its delay decreases to zero.
            if (PACKETS.offer(event.packet(), TIMING.delayAt(now))) event.cancel();
            else flush(client);
        }
    }

    private static synchronized void receive(PacketReceiveEvent.Apply event) {
        if (player == null) return;
        Minecraft client = Minecraft.getInstance();
        if (!sameContext(client) || event.listener() != client.getConnection()) {
            discard();
            return;
        }
        if (event.packet() instanceof ClientboundPlayerPositionPacket
                || LagPacketPolicy.mustDiscardOnIncoming(event.packet())) {
            // Never replay positions from before a teleport/correction into the new state.
            discard();
        } else if (LagPacketPolicy.mustFlushOnIncoming(client, event.packet())
                || event.packet() instanceof ClientboundDamageEventPacket damage
                        && damage.entityId() == player.getId()) {
            flush(client);
        }
    }

    static synchronized void flush(Minecraft client) {
        player = null;
        level = null;
        spear = null;
        TIMING.clear();
        PACKETS.flushClient(client);
    }

    private static synchronized void discard() {
        player = null;
        level = null;
        spear = null;
        TIMING.clear();
        PACKETS.discard();
    }

    static final class Timing {
        private long holdUntil;
        private int delayMs;

        void start(long now, int delay, int duration) {
            delayMs = delay;
            holdUntil = now + duration;
        }

        long delayAt(long now) {
            // Reduce delay by half the elapsed time: packets catch up at 2x their
            // original cadence instead of sharing one final release deadline.
            return Math.max(0L, delayMs - Math.max(0L, now - holdUntil) / 2L);
        }

        boolean active(long now) {
            return delayMs > 0 && now < holdUntil + delayMs * 2L;
        }

        void clear() {
            holdUntil = 0;
            delayMs = 0;
        }
    }
}
