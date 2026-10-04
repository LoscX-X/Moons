package com.blanoir.moons.client.module.impl.misc.antibot;

import com.blanoir.moons.client.access.PacketAccess;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S13PacketDestroyEntities;
import net.minecraft.network.play.server.S14PacketEntity;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Collects current player facts once per tick; target queries only read cached verdicts. */
final class AntiBotState {
    private final Map<Integer, TrackedPlayer> trackedPlayers = new HashMap<>();
    private Object levelIdentity;
    private Object connectionIdentity;
    private Object localPlayerIdentity;

    void tick(Minecraft client) {
        synchronizeLevel(client);
        if (!AntiBot.isEnabled()
                || client == null
                || client.thePlayer == null
                || client.theWorld == null) return;
        var connection = client.getNetHandler();
        if (connection == null) {
            reset();
            return;
        }

        Map<String, Integer> profileNames = new HashMap<>();
        for (var info : connection.getPlayerInfoMap()) {
            profileNames.merge(normalizeName(info.getGameProfile().getName()), 1, Integer::sum);
        }
        Set<Integer> present = new HashSet<>();
        for (EntityPlayer player : client.theWorld.playerEntities) {
            if (player == client.thePlayer) continue;
            present.add(player.getEntityId());
            TrackedPlayer tracked = track(player);
            var info = connection.getPlayerInfo(player.getUniqueID());
            String name = normalizeName(player.getGameProfile().getName());
            int ownEntry =
                    info != null
                                    && Objects.equals(
                                            normalizeName(info.getGameProfile().getName()), name)
                            ? 1
                            : 0;
            // Let movement evidence expire even when a player stops sending moves.
            if (++tracked.evidenceTicks >= 20) {
                tracked.invalidGroundVl /= 2;
                tracked.evidenceTicks = 0;
            }
            tracked.detector.observe(
                    new AdvancedBotDetector.Facts(
                            player.ticksExisted,
                            player.rotationPitch,
                            tracked.invalidGroundVl,
                            info == null,
                            !name.isEmpty() && profileNames.getOrDefault(name, 0) > ownEntry));
        }
        trackedPlayers.keySet().retainAll(present);
    }

    void handlePacket(Minecraft client, Packet<?> packet) {
        synchronizeLevel(client);
        if (!AntiBot.isEnabled() || client == null || client.theWorld == null || packet == null)
            return;
        if (packet instanceof S14PacketEntity movement) {
            if (!(movement instanceof S14PacketEntity.S15PacketEntityRelMove
                            || movement instanceof S14PacketEntity.S17PacketEntityLookMove)
                    || !(movement.getEntity(client.theWorld) instanceof EntityPlayer player)
                    || player == client.thePlayer) return;
            TrackedPlayer tracked = track(player);
            if (movement.getOnGround() && PacketAccess.hasVerticalDelta(movement)) {
                tracked.invalidGroundVl = Math.min(50, tracked.invalidGroundVl + 1);
            } else if (!movement.getOnGround()) {
                tracked.invalidGroundVl /= 2;
            } else {
                tracked.invalidGroundVl = Math.max(0, tracked.invalidGroundVl - 1);
            }
        } else if (packet instanceof S13PacketDestroyEntities remove) {
            PacketAccess.removedEntityIds(remove).forEach(trackedPlayers::remove);
        }
    }

    boolean isBot(Minecraft client, EntityPlayer player) {
        synchronizeLevel(client);
        if (client == null || client.getNetHandler() == null) return false;
        TrackedPlayer tracked = trackedPlayers.get(player.getEntityId());
        return tracked != null && tracked.player == player && tracked.detector.isBot();
    }

    void reset() {
        trackedPlayers.clear();
    }

    private void synchronizeLevel(Minecraft client) {
        Object currentLevel = client == null ? null : client.theWorld;
        Object currentConnection = client == null ? null : client.getNetHandler();
        Object currentPlayer = client == null ? null : client.thePlayer;
        if (currentLevel != levelIdentity
                || currentConnection != connectionIdentity
                || currentPlayer != localPlayerIdentity) {
            reset();
            levelIdentity = currentLevel;
            connectionIdentity = currentConnection;
            localPlayerIdentity = currentPlayer;
        }
    }

    private static String normalizeName(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT);
    }

    private TrackedPlayer track(EntityPlayer player) {
        TrackedPlayer tracked = trackedPlayers.get(player.getEntityId());
        if (tracked == null || tracked.player != player) {
            tracked = new TrackedPlayer(player);
            trackedPlayers.put(player.getEntityId(), tracked);
        }
        return tracked;
    }

    private static final class TrackedPlayer {
        private final EntityPlayer player;
        private final AdvancedBotDetector detector = new AdvancedBotDetector();
        private int invalidGroundVl;
        private int evidenceTicks;

        private TrackedPlayer(EntityPlayer player) {
            this.player = player;
        }
    }
}
