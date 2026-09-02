package com.blanoir.moons.client.event.network;

import com.blanoir.moons.client.event.Cancellable;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;

public final class PacketReceiveEvent {
    private PacketReceiveEvent() {
    }

    public static final class Pre implements Cancellable {
        private final Packet<?> packet;
        private final PacketListener listener;
        private final PacketThread thread;
        private boolean cancelled;
        private boolean bundleExpansionRequested;

        public Pre(Packet<?> packet, PacketListener listener, PacketThread thread) {
            this.packet = packet;
            this.listener = listener;
            this.thread = thread;
        }

        public Packet<?> packet() { return packet; }
        public PacketListener listener() { return listener; }
        public PacketThread thread() { return thread; }
        public boolean bundleExpansionRequested() { return bundleExpansionRequested; }
        public void requestBundleExpansion() { bundleExpansionRequested = true; }
        @Override public boolean isCancelled() { return cancelled; }
        @Override public void cancel() { cancelled = true; }
    }

    /** Client-thread boundary immediately after vanilla's thread handoff check. */
    public record Apply(Packet<?> packet, PacketListener listener) {
    }
}
