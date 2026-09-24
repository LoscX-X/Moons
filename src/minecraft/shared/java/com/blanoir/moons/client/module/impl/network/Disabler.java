package com.blanoir.moons.client.module.impl.network;

import com.blanoir.moons.client.access.PacketAccess;
import com.blanoir.moons.client.config.settings.BooleanSetting;
import com.blanoir.moons.client.event.EventBus;
import com.blanoir.moons.client.event.EventPriority;
import com.blanoir.moons.client.event.network.PacketSendEvent;
import com.blanoir.moons.client.management.network.LagUtils;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.phys.Vec3;

import java.util.concurrent.atomic.AtomicLong;

/** Suppresses position correction acknowledgements while enabled. */
public final class Disabler {
    private static final BooleanSetting ENABLED =
            new BooleanSetting.Builder().name("disabler.enabled").defaultValue(false).build();
    private static final AtomicLong BLOCKED_CONFIRMATIONS = new AtomicLong();
    // Vanilla generates acknowledgements on the client thread after applying the correction.
    private static volatile Pending pending;

    private Disabler() {}

    public static void initPacketListeners() {
        EventBus.PACKET_SEND_PRE.register(
                "Disabler.teleportConfirm", EventPriority.HIGHEST, Disabler::onPacketSend);
        EventBus.CLIENT_CONTEXT_CHANGED.register(
                "Disabler.context", event -> discardIfStale(event.client()));
    }

    private static void onPacketSend(PacketSendEvent.Pre event) {
        if (!isEnabled()
                || !(event.packet() instanceof ServerboundAcceptTeleportationPacket confirmation)) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        // Only hold acknowledgements whose active-session correction we can safely restore.
        if (!client.isSameThread()
                || client.player == null
                || client.level == null
                || client.getConnection() == null
                || client.getConnection().getConnection() != event.connection()) {
            // An acknowledgement we let through must not later be replayed as a duplicate.
            if (pending != null && pending.connection() == event.connection()) pending = null;
            return;
        }
        LocalPlayer player = client.player;
        Pending previous = pending;
        if (previous == null || !previous.matches(client)) BLOCKED_CONFIRMATIONS.set(0L);
        // Vanilla IDs are nonnegative; Grim's packet-only setbacks use negative IDs.
        // Preserve vanilla's outstanding acknowledgement even if Grim corrects us afterwards.
        ServerboundAcceptTeleportationPacket vanillaConfirmation =
                PacketAccess.teleportConfirmationId(confirmation) >= 0
                        ? confirmation
                        : previous != null && previous.matches(client)
                                ? previous.vanillaConfirmation()
                                : null;
        pending =
                new Pending(
                        event.connection(),
                        client.level,
                        player,
                        confirmation,
                        vanillaConfirmation,
                        player.position(),
                        player.getDeltaMovement(),
                        player.getYRot(),
                        player.getXRot());
        // Retain original packet objects: 26.3 also embeds position/rotation in them.
        // Cancel before lag listeners can buffer and later replay the acknowledgement.
        event.cancel();
        BLOCKED_CONFIRMATIONS.incrementAndGet();
    }

    public static boolean isEnabled() {
        return ENABLED.get();
    }

    public static int setEnabled(Minecraft client, boolean value) {
        if (client != null && !client.isSameThread()) {
            client.execute(() -> setEnabled(client, value));
            return 1;
        }
        if (value && !isEnabled()) BLOCKED_CONFIRMATIONS.set(0L);
        ENABLED.set(value);
        if (!value) recover(client);
        return 1;
    }

    private static void discardIfStale(Minecraft client) {
        // Context events arrive at tick start, possibly AFTER the new world's first correction.
        if (pending != null && !pending.matches(client)) pending = null;
    }

    private static void recover(Minecraft client) {
        Pending saved = pending;
        pending = null;
        if (saved == null || !saved.matches(client)) return;

        // Restore absolute values captured AFTER vanilla applied relative correction flags.
        // Reapplying the original incoming packet here would apply relative offsets twice.
        LocalPlayer player = saved.player();
        Vec3 position = saved.position();
        player.absSnapTo(position.x, position.y, position.z, saved.yaw(), saved.pitch());
        player.setOldPosAndRot(position, saved.yaw(), saved.pitch());
        player.setDeltaMovement(saved.movement());
        LagUtils.replay(
                () -> {
                    if (saved.vanillaConfirmation() != null
                            && saved.vanillaConfirmation() != saved.confirmation()) {
                        saved.connection().send(saved.vanillaConfirmation());
                    }
                    saved.connection().send(saved.confirmation());
                    saved.connection()
                            .send(
                                    new ServerboundMovePlayerPacket.PosRot(
                                            position.x,
                                            position.y,
                                            position.z,
                                            saved.yaw(),
                                            saved.pitch(),
                                            false,
                                            false));
                });
    }

    /** Unload must not retain player/world references; no persisted setting is changed. */
    public static void discardPending() {
        pending = null;
    }

    /** Reports only the current context; a held acknowledgement is not a server exemption. */
    public static String statusTag() {
        Pending saved = pending;
        if (saved == null || !saved.matches(Minecraft.getInstance())) return "Waiting";
        return "Held "
                + BLOCKED_CONFIRMATIONS.get()
                + " | ID "
                + PacketAccess.teleportConfirmationId(saved.confirmation());
    }

    public static String hudTag() {
        Pending saved = pending;
        return saved == null || !saved.matches(Minecraft.getInstance()) ? "Waiting" : "Held";
    }

    private record Pending(
            Connection connection,
            ClientLevel level,
            LocalPlayer player,
            ServerboundAcceptTeleportationPacket confirmation,
            ServerboundAcceptTeleportationPacket vanillaConfirmation,
            Vec3 position,
            Vec3 movement,
            float yaw,
            float pitch) {
        boolean matches(Minecraft client) {
            return client != null
                    && client.level == level
                    && client.player == player
                    && client.getConnection() != null
                    && client.getConnection().getConnection() == connection
                    && connection.isConnected();
        }
    }
}
