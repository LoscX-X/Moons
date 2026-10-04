package com.blanoir.moons.client.management.network;

import net.minecraft.client.Minecraft;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.*;
import net.minecraft.network.play.server.*;

/** Outgoing action boundaries and incoming correction/context boundaries for all lag modes. */
public final class LagPacketPolicy {
    private LagPacketPolicy() {}

    public static boolean mustFlushBefore(Packet<?> p) {
        return p instanceof C02PacketUseEntity
                || p instanceof C0APacketAnimation
                || p instanceof C08PacketPlayerBlockPlacement
                || p instanceof C12PacketUpdateSign
                || p instanceof C07PacketPlayerDigging
                || p instanceof C0EPacketClickWindow
                || p instanceof C11PacketEnchantItem
                || p instanceof C0DPacketCloseWindow
                || p instanceof C09PacketHeldItemChange
                || p instanceof C10PacketCreativeInventoryAction
                || p instanceof C19PacketResourcePackStatus;
    }

    public static boolean mustDiscardOnIncoming(Packet<?> p) {
        return p instanceof S07PacketRespawn
                || p instanceof S01PacketJoinGame
                || p instanceof S40PacketDisconnect;
    }

    public static boolean mustFlushOnIncoming(Minecraft client, Packet<?> p) {
        if (p instanceof S08PacketPlayerPosLook
                || p instanceof S06PacketUpdateHealth
                || mustDiscardOnIncoming(p)) return true;
        if (p instanceof S12PacketEntityVelocity velocity)
            return client != null
                    && client.thePlayer != null
                    && velocity.getEntityID() == client.thePlayer.getEntityId()
                    && (velocity.getMotionX() != 0
                            || velocity.getMotionY() != 0
                            || velocity.getMotionZ() != 0);
        return p instanceof S27PacketExplosion e
                && (e.func_149149_c() != 0 || e.func_149144_d() != 0 || e.func_149147_e() != 0);
    }
}
