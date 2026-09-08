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
 * <p>Source attribution is recorded in THIRD_PARTY_NOTICES.md.
 */
public final class TrackedEntityPosition {
    private final VecDeltaCodec codec = new VecDeltaCodec();

    public TrackedEntityPosition() {
        this(Vec3.ZERO);
    }

    public TrackedEntityPosition(Entity entity) {
        this(entity.getPositionCodec().getBase());
    }

    private TrackedEntityPosition(Vec3 initialPosition) {
        codec.setBase(initialPosition);
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
        Vec3 trackedPos =
                switch (packet) {
                    case ClientboundMoveEntityPacket move when move.getEntity(level) == target ->
                            codec.decode(move.getXa(), move.getYa(), move.getZa());
                    case ClientboundTeleportEntityPacket teleport
                            when teleport.id() == target.getId() ->
                            teleport.change().position();
                    case ClientboundEntityPositionSyncPacket sync
                            when sync.id() == target.getId() ->
                            sync.values().position();
                    case null, default -> null;
                };

        if (trackedPos == null) {
            return null;
        }

        this.base(trackedPos);
        return trackedPos;
    }
}
