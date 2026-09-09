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
        return net.minecraft.network.protocol.game.ServerboundPunchPacket.INSTANCE;
    }

    public static boolean isSwingPacket(Packet<?> packet) {
        return packet instanceof net.minecraft.network.protocol.game.ServerboundPunchPacket;
    }

    public static float useItemYaw(
            net.minecraft.network.protocol.game.ServerboundUseItemPacket packet) {
        return packet.yRot();
    }

    public static float useItemPitch(
            net.minecraft.network.protocol.game.ServerboundUseItemPacket packet) {
        return packet.xRot();
    }

    public static net.minecraft.world.phys.BlockHitResult useOnHit(
            net.minecraft.network.protocol.game.ServerboundUseItemOnPacket packet) {
        return packet.hitResult();
    }

    public static int useOnSequence(
            net.minecraft.network.protocol.game.ServerboundUseItemOnPacket packet) {
        return packet.sequence();
    }

    public static it.unimi.dsi.fastutil.ints.IntList removedEntityIds(
            net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket packet) {
        return packet.entityIds();
    }

    public static net.minecraft.world.phys.Vec3 syncPosition(
            net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket packet) {
        return packet.position().endPosition();
    }

    public static net.minecraft.world.phys.Vec3 decodeEntityDelta(
            net.minecraft.network.protocol.game.ClientboundMoveEntityPacket packet,
            net.minecraft.network.protocol.game.VecDeltaCodec codec) {
        return packet.getPositionDelta().decode(codec).endPosition();
    }

    public static boolean hasVerticalDelta(
            net.minecraft.network.protocol.game.ClientboundMoveEntityPacket packet) {
        return switch (packet.getPositionDelta()) {
            case net.minecraft.network.protocol.game.VecDelta.Linear delta -> delta.ya() != 0;
            case net.minecraft.network.protocol.game.VecDelta.Stepped delta ->
                    delta.steps().stream().anyMatch(step -> step.ya() != 0);
        };
    }
}
