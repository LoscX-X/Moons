package com.blanoir.moons.client.management.network;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.network.protocol.game.VecDeltaCodec;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Tracks delayed entity positions from vanilla movement packets.
 *
 * <p>This is a direct port of LiquidBounce's
 * {@code net.ccbluex.liquidbounce.features.blink.TrackedEntityPosition}.
 */
public final class TrackedEntityPosition {
    private final VecDeltaCodec codec = new VecDeltaCodec();

    public TrackedEntityPosition() {
        this.codec.setBase(Vec3.ZERO);
    }

    public TrackedEntityPosition(Entity entity) {
        this.codec.setBase(entity.getPositionCodec().getBase());
    }

    public Vec3 base() {
        return this.codec.getBase();
    }

    public void base(Vec3 value) {
        this.codec.setBase(value);
    }

    public void setBaseFrom(Entity entity) {
        this.codec.setBase(entity.getPositionCodec().getBase());
    }

    /**
     * Advances the tracked position with a movement packet, returning the new tracked
     * position, or {@code null} when the packet does not move the tracked entity.
     */
    public Vec3 handlePacket(Packet<?> packet, ClientLevel level, Entity target) {
        Vec3 trackedPos;

        if (packet instanceof ClientboundMoveEntityPacket movePacket
                && movePacket.getEntity(level) == target) {
            trackedPos =
                    this.codec.decode(movePacket.getXa(), movePacket.getYa(), movePacket.getZa());
        } else if (packet instanceof ClientboundTeleportEntityPacket teleportPacket
                && teleportPacket.id() == target.getId()) {
            trackedPos = teleportPacket.change().position();
        } else if (packet instanceof ClientboundEntityPositionSyncPacket syncPacket
                && syncPacket.id() == target.getId()) {
            trackedPos = syncPacket.values().position();
        } else {
            return null;
        }

        if (trackedPos == null) {
            return null;
        }

        this.base(trackedPos);
        return trackedPos;
    }
}
