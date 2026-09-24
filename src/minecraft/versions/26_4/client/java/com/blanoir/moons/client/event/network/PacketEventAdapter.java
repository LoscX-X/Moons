package com.blanoir.moons.client.event.network;

import com.blanoir.moons.client.access.GameAccess;
import com.blanoir.moons.client.event.EventBus;
import com.blanoir.moons.client.management.lease.RotationLease;

import net.minecraft.client.Minecraft;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;

/** Version-owned packet ingress, thread boundaries and vanilla bundle dispatch. */
public final class PacketEventAdapter {
    private PacketEventAdapter() {}

    public static void initPacketListeners() {
        EventBus.PACKET_RECEIVE_PRE.register(
                "PacketEventAdapter.bundle",
                event -> {
                    if (!(event.packet() instanceof ClientboundBundlePacket)) return;
                    PacketReceiveEvent.Bundle bundle = new PacketReceiveEvent.Bundle();
                    EventBus.PACKET_RECEIVE_BUNDLE.post(bundle);
                    if (bundle.expansionRequested()) event.requestBundleExpansion();
                });
    }

    public static boolean send(Object connection, Object packet) {
        PacketSendEvent.Pre event =
                new PacketSendEvent.Pre(
                        (Connection) connection, (Packet<?>) packet, packetThread());
        EventBus.PACKET_SEND_PRE.post(event);
        return !event.isCancelled();
    }

    public static void sent(Object connection, Object packet) {
        RotationLease.beginPacketObservation();
        try {
            EventBus.PACKET_SEND_POST.post(
                    new PacketSendEvent.Post(
                            (Connection) connection, (Packet<?>) packet, packetThread()));
        } finally {
            RotationLease.endPacketObservation();
        }
    }

    public static void receive(Object packet, Object listener, Runnable cancel) {
        if (packetThread() == PacketThread.CLIENT) return;
        PacketReceiveEvent.Pre event =
                new PacketReceiveEvent.Pre(
                        (Packet<?>) packet, (PacketListener) listener, PacketThread.NETWORK);
        EventBus.PACKET_RECEIVE_PRE.post(event);
        if (event.bundleExpansionRequested() && packet instanceof ClientboundBundlePacket bundle) {
            cancel.run();
            bundle.subPackets()
                    .forEach(
                            child -> GameAccess.dispatchIncoming(child, (PacketListener) listener));
        } else if (event.isCancelled()) {
            cancel.run();
        }
    }

    public static void apply(Object packet, Object listener) {
        // Each vanilla child handler publishes its own APPLY; never flatten bundles here.
        EventBus.PACKET_RECEIVE_APPLY.post(
                new PacketReceiveEvent.Apply((Packet<?>) packet, (PacketListener) listener));
    }

    private static PacketThread packetThread() {
        return Minecraft.getInstance().isSameThread() ? PacketThread.CLIENT : PacketThread.NETWORK;
    }
}
