package com.blanoir.moons.client.management.network;

import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.entity.Entity;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S14PacketEntity;
import net.minecraft.network.play.server.S18PacketEntityTeleport;
import net.minecraft.util.Vec3;

/** Vanilla 1.8.9 entity movement is encoded in units of 1/32 block, not modern 1/4096. */
public final class TrackedEntityPosition {
    private Vec3 position = new Vec3(0, 0, 0);

    public TrackedEntityPosition() {}

    public TrackedEntityPosition(Entity entity) {
        setBaseFrom(entity);
    }

    public Vec3 base() {
        return position;
    }

    public void base(Vec3 value) {
        position = value;
    }

    public void setBaseFrom(Entity entity) {
        position =
                new Vec3(
                        entity.serverPosX / 32.0,
                        entity.serverPosY / 32.0,
                        entity.serverPosZ / 32.0);
    }

    public Vec3 handlePacket(Packet<?> packet, WorldClient world, Entity target) {
        if (packet instanceof S14PacketEntity move && move.getEntity(world) == target)
            position =
                    position.addVector(
                            move.func_149062_c() / 32.0,
                            move.func_149061_d() / 32.0,
                            move.func_149064_e() / 32.0);
        else if (packet instanceof S18PacketEntityTeleport teleport
                && teleport.getEntityId() == target.getEntityId())
            position =
                    new Vec3(
                            teleport.getX() / 32.0, teleport.getY() / 32.0, teleport.getZ() / 32.0);
        else return null;
        return position;
    }
}
