package com.blanoir.tempos.client.module.impl.combat;

import com.blanoir.tempos.client.event.EventBus;
import com.blanoir.tempos.client.event.EventPriority;
import com.blanoir.tempos.client.event.network.PacketReceiveEvent;
import com.blanoir.tempos.client.event.network.PacketSendEvent;
import com.blanoir.tempos.client.manager.network.DelayedValueQueue;
import com.blanoir.tempos.client.manager.network.LagPacketPolicy;
import com.blanoir.tempos.client.manager.network.PacketDelayQueue;
import com.blanoir.tempos.client.module.framework.SubFeature;
import com.blanoir.tempos.client.lifecycle.ClientReady;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.item.Item;

/** Per-packet movement delay during Lunge, followed by gradual FIFO catch-up. */
final class AutoSpearFakeLag extends SubFeature<AutoSpear> {
    private final PacketDelayQueue packets = new PacketDelayQueue(32);
    private LocalPlayer player;
    private ClientLevel level;
    private Item spear;
    private int slot;
    private final Timing timing = new Timing();

    AutoSpearFakeLag(AutoSpear parent) {
        super(parent);
    }

    @Override
    public void registerEvents() {
        EventBus.TICK.register("AutoSpear.fakeLagTick", event -> tick(event.client()));
        EventBus.CLIENT_CONTEXT_CHANGED.register("AutoSpear.fakeLagContext", event -> discard());
    }

    void initPacketListeners() {
        EventBus.PACKET_SEND_PRE.register("AutoSpear.fakeLagSend", EventPriority.HIGH, this::send);
        EventBus.PACKET_RECEIVE_APPLY.register("AutoSpear.fakeLagReceive", this::receive);
    }

    synchronized void start(Minecraft client) {
        flush(client);
        if (!parent().isEnabled()
                || !parent().fakeLagEnabled()
                || parent().impactActive()
                || !ClientReady.aliveGameplay(client)) return;
        packets.observeClient(client);
        player = client.player;
        level = client.level;
        spear = player.getMainHandItem().getItem();
        slot = player.getInventory().getSelectedSlot();
        timing.start(
                DelayedValueQueue.nowMillis(),
                parent().fakeLagDelayMs(),
                parent().fakeLagDurationMs());
    }

    private boolean sameContext(Minecraft client) {
        return player != null
                && ClientReady.world(client)
                && client.player == player
                && client.level == level;
    }

    private boolean ready(Minecraft client) {
        return sameContext(client)
                && parent().isEnabled()
                && parent().fakeLagEnabled()
                && ClientReady.aliveGameplay(client)
                && client.isWindowActive()
                && !client.isPaused()
                && !player.isSpectator()
                && player.getInventory().getSelectedSlot() == slot
                && player.getMainHandItem().is(spear);
    }

    private synchronized void tick(Minecraft client) {
        if (player == null) return;
        if (!sameContext(client)) {
            discard();
        } else if (!ready(client)) {
            flush(client);
        } else {
            packets.observeClient(client);
            packets.flushDue();
            if (!timing.active(DelayedValueQueue.nowMillis()) && packets.isEmpty()) discard();
        }
    }

    private synchronized void send(PacketSendEvent.Pre event) {
        if (player == null || DelayedValueQueue.isReplaying()) return;
        Minecraft client = Minecraft.getInstance();
        if (!sameContext(client)
                || client.getConnection() == null
                || event.connection() != client.getConnection().getConnection()) {
            discard();
            return;
        }
        packets.observe(event.connection(), client.level);
        if (!ready(client) || packets.isFull() || LagPacketPolicy.mustFlushBefore(event.packet())) {
            // Actions, especially the next STAB, follow all earlier positions/rotations.
            flush(client);
            return;
        }
        packets.flushDue();
        if (!sameContext(client)) return;
        long now = DelayedValueQueue.nowMillis();
        if (!timing.active(now) && packets.isEmpty()) {
            discard();
            return;
        }
        if (event.packet() instanceof ServerboundMovePlayerPacket) {
            // New movement stays behind the tail while its delay decreases to zero.
            if (packets.offer(event.packet(), timing.delayAt(now))) event.cancel();
            else flush(client);
        }
    }

    private synchronized void receive(PacketReceiveEvent.Apply event) {
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

    synchronized void flush(Minecraft client) {
        player = null;
        level = null;
        spear = null;
        timing.clear();
        packets.flushClient(client);
    }

    synchronized void discard() {
        player = null;
        level = null;
        spear = null;
        timing.clear();
        packets.discard();
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
