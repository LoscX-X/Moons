package com.blanoir.tempos.client.module.impl.network.backtrack;

import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.network.PacketListener;
import net.minecraft.network.PacketProcessor;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

/** Exercises the production receive boundary, then replays real packets through its FIFO. */
final class BacktrackAdmissionVerification {
    static void verify() throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        var unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        var unsafe = (sun.misc.Unsafe) unsafeField.get(null);
        var instanceField = Minecraft.class.getDeclaredField("instance");
        instanceField.setAccessible(true);
        Object previousClient = instanceField.get(null);
        var client = (Minecraft) unsafe.allocateInstance(Minecraft.class);
        var processor = new RecordingProcessor();
        var config = new BacktrackConfig();
        boolean previousEnabled = config.enabled();
        try {
            for (var field : Minecraft.class.getDeclaredFields()) {
                if (field.getType() == PacketProcessor.class) {
                    field.setAccessible(true);
                    field.set(client, processor);
                }
            }
            instanceField.set(null, client);
            config.setEnabled(true);
            var runtime = new BacktrackRuntime(config);
            var handled = new ArrayList<Packet<?>>();
            var listener = (ClientGamePacketListener) Proxy.newProxyInstance(
                    ClientGamePacketListener.class.getClassLoader(),
                    new Class<?>[] {ClientGamePacketListener.class},
                    (proxy, method, arguments) -> {
                        if (method.getName().startsWith("handle")) {
                            handled.add((Packet<?>) arguments[0]);
                            return null;
                        }
                        throw new AssertionError("Unexpected listener call: " + method.getName());
                    });
            // No movement fields are read at admission; this keeps the fixture version-neutral.
            var movement = (Packet<?>) unsafe.allocateInstance(ClientboundMoveEntityPacket.Pos.class);
            var ping = new ClientboundPingPacket(7);
            var keepAlive = new ClientboundKeepAlivePacket(8L);
            for (Packet<?> packet : List.of(movement, ping, keepAlive)) {
                require(runtime.handleIncomingPacket(packet, listener),
                        "Backtrack lets a confirmation overtake delayed movement: "
                                + packet.getClass().getSimpleName());
            }
            require(processor.queued.size() == 3, "All state/confirmation packets reach one processor");
            require(!runtime.handleIncomingPacket(
                            new ClientboundSystemChatPacket(Component.literal("chat"), false), listener),
                    "Chat remains responsive outside the delayed state stream");
            var queue = new BacktrackPacketQueue<Packet<?>>(8);
            queue.start(60);
            for (int i = 0; i < processor.queued.size(); i++) {
                var envelope = processor.queued.get(i);
                var original = envelope.getClass().getDeclaredMethod("original");
                original.setAccessible(true);
                require(queue.offer((Packet<?>) original.invoke(envelope), i), "Queue admits packet");
            }
            queue.releaseDue(59, packet -> apply(packet, listener));
            require(handled.isEmpty(), "No early update or acknowledgement");
            queue.releaseDue(60, packet -> apply(packet, listener));
            require(handled.equals(List.of(movement)), "Movement is handled before its confirmation");
            queue.releaseDue(61, packet -> apply(packet, listener));
            require(handled.equals(List.of(movement, ping)), "Ping handler cannot overtake movement");
            queue.releaseAll(packet -> apply(packet, listener));
            require(handled.equals(List.of(movement, ping, keepAlive)), "Flush preserves remaining order");
            config.setEnabled(false);
            require(!runtime.handleIncomingPacket(ping, listener), "Disabled Backtrack does not intercept");
        } finally {
            config.setEnabled(previousEnabled);
            instanceField.set(null, previousClient);
            processor.close();
        }
        System.out.println("TEMPOS_BACKTRACK_ADMISSION_VERIFIED movement-before-ping-before-keepalive");
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void apply(Packet packet, ClientGamePacketListener listener) {
        packet.handle(listener);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class RecordingProcessor extends PacketProcessor {
        final List<Packet<?>> queued = new ArrayList<>();

        RecordingProcessor() {
            super(Thread.currentThread());
        }

        @Override
        @SuppressWarnings({"rawtypes", "unchecked"})
        public void scheduleIfPossible(PacketListener listener, Packet packet) {
            queued.add(packet);
        }
    }
}
