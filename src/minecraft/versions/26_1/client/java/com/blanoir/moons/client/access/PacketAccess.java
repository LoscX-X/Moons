package com.blanoir.moons.client.access;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;

import java.util.function.Consumer;

/** Version-owned bundle iteration; applying or replaying children is the caller's decision. */
public final class PacketAccess {
    private PacketAccess() {}

    public static void forEachPacket(Packet<?> packet, Consumer<Packet<?>> consumer) {
        if (packet instanceof ClientboundBundlePacket bundle) {
            bundle.subPackets().forEach(consumer);
        } else {
            consumer.accept(packet);
        }
    }
}
