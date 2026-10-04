package com.blanoir.moons.client.event.network;

import com.blanoir.moons.client.event.Cancellable;

import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;

public final class PacketSendEvent {
    private PacketSendEvent() {}

    public static final class Pre implements Cancellable {
        private final NetworkManager connection;
        private final Packet<?> packet;
        private final PacketThread thread;
        private boolean cancelled;

        public Pre(NetworkManager connection, Packet<?> packet, PacketThread thread) {
            this.connection = connection;
            this.packet = packet;
            this.thread = thread;
        }

        public NetworkManager connection() {
            return connection;
        }

        public Packet<?> packet() {
            return packet;
        }

        public PacketThread thread() {
            return thread;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public void cancel() {
            cancelled = true;
        }
    }

    public record Post(NetworkManager connection, Packet<?> packet, PacketThread thread) {}
}
