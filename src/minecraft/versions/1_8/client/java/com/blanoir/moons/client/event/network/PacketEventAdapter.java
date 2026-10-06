package com.blanoir.moons.client.event.network;

import com.blanoir.moons.client.event.EventBus;
import com.blanoir.moons.client.manager.lease.RotationLease;

import net.minecraft.client.Minecraft;
import net.minecraft.network.INetHandler;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;

/** 1.8 packet ingress and thread boundaries; this protocol has no bundled packets. */
public final class PacketEventAdapter {
    private PacketEventAdapter() {}

    public static boolean send(Object connection, Object packet) {
        PacketSendEvent.Pre event =
                new PacketSendEvent.Pre(
                        (NetworkManager) connection, (Packet<?>) packet, packetThread());
        EventBus.PACKET_SEND_PRE.post(event);
        return !event.isCancelled();
    }

    public static void sent(Object connection, Object packet) {
        RotationLease.beginPacketObservation();
        try {
            EventBus.PACKET_SEND_POST.post(
                    new PacketSendEvent.Post(
                            (NetworkManager) connection, (Packet<?>) packet, packetThread()));
        } finally {
            RotationLease.endPacketObservation();
        }
    }

    public static void receive(Object packet, Object connection, Runnable cancel) {
        // This is NetworkManager.channelRead0, before vanilla's main-thread handoff.
        PacketReceiveEvent.Pre event =
                new PacketReceiveEvent.Pre(
                        (Packet<?>) packet, listener(connection), packetThread());
        EventBus.PACKET_RECEIVE_PRE.post(event);
        if (event.isCancelled()) cancel.run();
    }

    public static void apply(Object packet, Object connection) {
        // Called after PacketThreadUtil's handoff, before the handler changes game state.
        EventBus.PACKET_RECEIVE_APPLY.post(
                new PacketReceiveEvent.Apply((Packet<?>) packet, listener(connection)));
    }

    private static INetHandler listener(Object connection) {
        return connection instanceof NetworkManager manager
                ? manager.getNetHandler()
                : (INetHandler) connection;
    }

    private static PacketThread packetThread() {
        return Minecraft.getMinecraft().isCallingFromMinecraftThread()
                ? PacketThread.CLIENT
                : PacketThread.NETWORK;
    }
}
