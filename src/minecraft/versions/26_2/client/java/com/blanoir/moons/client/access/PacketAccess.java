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

    public static Packet<?> swingPacket(net.minecraft.world.InteractionHand hand) {
        return new net.minecraft.network.protocol.game.ServerboundSwingPacket(hand);
    }

    public static boolean isSwingPacket(Packet<?> packet) {
        return packet instanceof net.minecraft.network.protocol.game.ServerboundSwingPacket;
    }

    public static float useItemYaw(
            net.minecraft.network.protocol.game.ServerboundUseItemPacket packet) {
        return packet.getYRot();
    }

    public static float useItemPitch(
            net.minecraft.network.protocol.game.ServerboundUseItemPacket packet) {
        return packet.getXRot();
    }

    public static net.minecraft.world.phys.BlockHitResult useOnHit(
            net.minecraft.network.protocol.game.ServerboundUseItemOnPacket packet) {
        return packet.getHitResult();
    }

    public static int useOnSequence(
            net.minecraft.network.protocol.game.ServerboundUseItemOnPacket packet) {
        return packet.getSequence();
    }

    public static it.unimi.dsi.fastutil.ints.IntList removedEntityIds(
            net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket packet) {
        return packet.getEntityIds();
    }

    public static net.minecraft.world.phys.Vec3 syncPosition(
            net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket packet) {
        return packet.values().position();
    }

    public static net.minecraft.world.phys.Vec3 decodeEntityDelta(
            net.minecraft.network.protocol.game.ClientboundMoveEntityPacket packet,
            net.minecraft.network.protocol.game.VecDeltaCodec codec) {
        return codec.decode(packet.getXa(), packet.getYa(), packet.getZa());
    }

    public static boolean hasVerticalDelta(
            net.minecraft.network.protocol.game.ClientboundMoveEntityPacket packet) {
        return packet.getYa() != 0;
    }
}
