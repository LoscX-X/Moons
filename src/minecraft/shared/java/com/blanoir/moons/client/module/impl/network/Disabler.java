package com.blanoir.moons.client.module.impl.network;

import com.blanoir.moons.client.config.settings.BooleanSetting;
import com.blanoir.moons.client.event.EventBus;
import com.blanoir.moons.client.event.EventPriority;
import com.blanoir.moons.client.event.network.PacketSendEvent;
import com.blanoir.moons.client.management.network.LagUtils;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;

import java.util.concurrent.atomic.AtomicLong;

/** Holds the actual 1.8.9 C06 acknowledgement generated while applying an S08 correction. */
public final class Disabler {
    private static final BooleanSetting ENABLED =
            new BooleanSetting.Builder().name("disabler.enabled").defaultValue(false).build();
    private static final AtomicLong BLOCKED_CONFIRMATIONS = new AtomicLong();
    private static volatile boolean applyingCorrection;
    private static volatile Pending pending;

    private Disabler() {}

    public static void initPacketListeners() {
        EventBus.PACKET_RECEIVE_APPLY.register(
                "Disabler.correction",
                event -> {
                    if (event.packet() instanceof S08PacketPlayerPosLook)
                        applyingCorrection = isEnabled();
                });
        EventBus.PACKET_SEND_PRE.register(
                "Disabler.teleportConfirm", EventPriority.HIGHEST, Disabler::onPacketSend);
        EventBus.CLIENT_CONTEXT_CHANGED.register(
                "Disabler.context",
                event -> {
                    if (pending != null && !pending.matches(event.client())) discardPending();
                });
        EventBus.TICK.register("Disabler.expireCorrection", event -> applyingCorrection = false);
    }

    private static void onPacketSend(PacketSendEvent.Pre event) {
        if (!isEnabled()
                || !applyingCorrection
                || !(event.packet() instanceof C03PacketPlayer.C06PacketPlayerPosLook packet))
            return;
        applyingCorrection = false;
        Minecraft client = Minecraft.getMinecraft();
        if (!client.isCallingFromMinecraftThread()
                || client.thePlayer == null
                || client.theWorld == null
                || client.getNetHandler() == null
                || client.getNetHandler().getNetworkManager() != event.connection()) return;
        if (pending == null || !pending.matches(client)) BLOCKED_CONFIRMATIONS.set(0);
        EntityPlayerSP player = client.thePlayer;
        pending =
                new Pending(
                        event.connection(),
                        client.theWorld,
                        player,
                        packet,
                        player.posX,
                        player.posY,
                        player.posZ,
                        player.motionX,
                        player.motionY,
                        player.motionZ,
                        player.rotationYaw,
                        player.rotationPitch);
        event.cancel();
        BLOCKED_CONFIRMATIONS.incrementAndGet();
    }

    public static boolean isEnabled() {
        return ENABLED.get();
    }

    public static int setEnabled(Minecraft client, boolean value) {
        if (client != null && !client.isCallingFromMinecraftThread()) {
            client.addScheduledTask(() -> setEnabled(client, value));
            return 1;
        }
        if (value && !isEnabled()) BLOCKED_CONFIRMATIONS.set(0);
        ENABLED.set(value);
        if (!value) recover(client);
        return 1;
    }

    private static void recover(Minecraft client) {
        Pending saved = pending;
        discardPending();
        if (saved == null || !saved.matches(client)) return;
        EntityPlayerSP player = saved.player();
        player.setPositionAndRotation(saved.x(), saved.y(), saved.z(), saved.yaw(), saved.pitch());
        player.prevPosX = player.posX;
        player.prevPosY = player.posY;
        player.prevPosZ = player.posZ;
        player.prevRotationYaw = player.rotationYaw;
        player.prevRotationPitch = player.rotationPitch;
        player.motionX = saved.motionX();
        player.motionY = saved.motionY();
        player.motionZ = saved.motionZ();
        LagUtils.replay(() -> saved.connection().sendPacket(saved.confirmation()));
    }

    public static void discardPending() {
        pending = null;
        applyingCorrection = false;
    }

    public static String statusTag() {
        Pending saved = pending;
        return saved == null || !saved.matches(Minecraft.getMinecraft())
                ? "Waiting"
                : "Held " + BLOCKED_CONFIRMATIONS.get();
    }

    public static String hudTag() {
        return pending == null ? "Waiting" : "Held";
    }

    private record Pending(
            NetworkManager connection,
            WorldClient level,
            EntityPlayerSP player,
            C03PacketPlayer.C06PacketPlayerPosLook confirmation,
            double x,
            double y,
            double z,
            double motionX,
            double motionY,
            double motionZ,
            float yaw,
            float pitch) {
        boolean matches(Minecraft client) {
            return client != null
                    && client.theWorld == level
                    && client.thePlayer == player
                    && client.getNetHandler() != null
                    && client.getNetHandler().getNetworkManager() == connection
                    && connection.isChannelOpen();
        }
    }
}
