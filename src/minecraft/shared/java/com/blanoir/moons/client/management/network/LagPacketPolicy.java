package com.blanoir.moons.client.management.network;

import com.blanoir.moons.client.access.PacketAccess;

import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket;
import net.minecraft.network.protocol.common.ServerboundResourcePackPacket;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundSetHealthPacket;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundContainerButtonClickPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.network.protocol.game.ServerboundContainerSlotStateChangedPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket;
import net.minecraft.network.protocol.game.ServerboundSignUpdatePacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.world.phys.Vec3;

/** Shared action and world-transition boundaries for outgoing Blink/FakeLag consumers. */
public final class LagPacketPolicy {
    private LagPacketPolicy() {}

    public static boolean mustFlushBefore(Packet<?> packet) {
        return packet instanceof ServerboundAttackPacket
                || packet instanceof ServerboundInteractPacket
                || PacketAccess.isSwingPacket(packet)
                || packet instanceof ServerboundUseItemOnPacket
                || packet instanceof ServerboundUseItemPacket
                || packet instanceof ServerboundSignUpdatePacket
                || packet instanceof ServerboundPlayerActionPacket
                || packet instanceof ServerboundContainerClickPacket
                || packet instanceof ServerboundContainerButtonClickPacket
                || packet instanceof ServerboundContainerClosePacket
                || packet instanceof ServerboundContainerSlotStateChangedPacket
                || packet instanceof ServerboundSetCarriedItemPacket
                || packet instanceof ServerboundSetCreativeModeSlotPacket
                || packet instanceof ServerboundResourcePackPacket;
    }

    /** Discard before resetting a mode: old-world actions must not be replayed after a transition. */
    public static boolean mustDiscardOnIncoming(Packet<?> packet) {
        return packet instanceof ClientboundRespawnPacket
                || packet instanceof ClientboundLoginPacket
                || packet instanceof ClientboundDisconnectPacket;
    }

    public static boolean mustFlushOnIncoming(Minecraft client, Packet<?> packet) {
        var currentPlayer = client == null ? null : client.player;
        if (packet instanceof ClientboundPlayerPositionPacket
                || packet instanceof ClientboundSetHealthPacket
                || packet instanceof ClientboundRespawnPacket
                || packet instanceof ClientboundLoginPacket
                || packet instanceof ClientboundDisconnectPacket) {
            return true;
        }

        if (packet instanceof ClientboundSetEntityMotionPacket(int id, Vec3 movement)) {
            return client != null
                    && currentPlayer != null
                    && id == currentPlayer.getId()
                    && !movement.equals(Vec3.ZERO);
        }

        return packet instanceof ClientboundExplodePacket explosion
                && explosion.playerKnockback().isPresent()
                && !explosion.playerKnockback().get().equals(Vec3.ZERO);
    }
}
