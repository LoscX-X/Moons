package com.blanoir.moons.client.access;

import it.unimi.dsi.fastutil.ints.*;

import net.minecraft.entity.Entity;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.*;
import net.minecraft.network.play.server.*;
import net.minecraft.util.*;
import net.minecraft.world.ChunkCoordIntPair;

import java.util.function.Consumer;

public final class PacketAccess {
    private PacketAccess() {}

    public static void forEachPacket(Packet<?> packet, Consumer<Packet<?>> consumer) {
        consumer.accept(packet);
    }

    public static ChunkCoordIntPair chunkPosition(S21PacketChunkData packet) {
        return new ChunkCoordIntPair(packet.getChunkX(), packet.getChunkZ());
    }

    public static Packet<?> swingPacket() {
        return new C0APacketAnimation();
    }

    public static boolean isSwingPacket(Packet<?> packet) {
        return packet instanceof C0APacketAnimation;
    }

    public static IntList removedEntityIds(S13PacketDestroyEntities packet) {
        return IntArrayList.wrap(packet.getEntityIDs());
    }

    public static float syncYaw(S18PacketEntityTeleport packet) {
        return packet.getYaw() * 360F / 256F;
    }

    public static float syncPitch(S18PacketEntityTeleport packet) {
        return packet.getPitch() * 360F / 256F;
    }

    public static Vec3 syncPosition(S18PacketEntityTeleport packet) {
        return new Vec3(packet.getX() / 32D, packet.getY() / 32D, packet.getZ() / 32D);
    }

    public static Vec3 decodeEntityDelta(S14PacketEntity packet, Entity entity) {
        return new Vec3(
                (entity.serverPosX + packet.func_149062_c()) / 32D,
                (entity.serverPosY + packet.func_149061_d()) / 32D,
                (entity.serverPosZ + packet.func_149064_e()) / 32D);
    }

    public static boolean hasVerticalDelta(S14PacketEntity packet) {
        return packet.func_149061_d() != 0;
    }

    public static MovingObjectPosition useOnHit(C08PacketPlayerBlockPlacement packet) {
        BlockPos p = packet.getPosition();
        int side = packet.getPlacedBlockDirection();
        return side == 255
                ? null
                : new MovingObjectPosition(
                        new Vec3(
                                p.getX() + packet.getPlacedBlockOffsetX(),
                                p.getY() + packet.getPlacedBlockOffsetY(),
                                p.getZ() + packet.getPlacedBlockOffsetZ()),
                        EnumFacing.getFront(side),
                        p);
    }
}
