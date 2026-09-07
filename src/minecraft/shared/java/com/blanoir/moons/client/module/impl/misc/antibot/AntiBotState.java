package com.blanoir.moons.client.module.impl.misc.antibot;

import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.world.entity.player.Player;

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
        if (!AntiBot.isEnabled() || client == null || client.player == null || client.level == null)
            return;
        var connection = client.getConnection();
        if (connection == null) {
            reset();
            return;
        }

        Map<String, Integer> profileNames = new HashMap<>();
        for (var info : connection.getOnlinePlayers()) {
            profileNames.merge(normalizeName(info.getProfile().name()), 1, Integer::sum);
        }
        Set<Integer> present = new HashSet<>();
        for (Player player : client.level.players()) {
            if (player == client.player) continue;
            present.add(player.getId());
            TrackedPlayer tracked = track(player);
            var info = connection.getPlayerInfo(player.getUUID());
            String name = normalizeName(player.getGameProfile().name());
            int ownEntry =
                    info != null && Objects.equals(normalizeName(info.getProfile().name()), name)
                            ? 1
                            : 0;
            // Let movement evidence expire even when a player stops sending moves.
            if (++tracked.evidenceTicks >= 20) {
                tracked.invalidGroundVl /= 2;
                tracked.evidenceTicks = 0;
            }
            tracked.detector.observe(
                    new AdvancedBotDetector.Facts(
                            player.tickCount,
                            player.getId(),
                            player.getXRot(),
                            tracked.invalidGroundVl,
                            info == null,
                            profileNames.getOrDefault(name, 0) > ownEntry,
                            (info == null ? player.getGameProfile() : info.getProfile())
                                    .properties()
                                    .isEmpty(),
                            info == null ? -1 : info.getLatency()));
        }
        trackedPlayers.keySet().retainAll(present);
    }

    void handlePacket(Minecraft client, Packet<?> packet) {
        synchronizeLevel(client);
        if (!AntiBot.isEnabled() || client == null || client.level == null || packet == null)
            return;
        if (packet instanceof ClientboundMoveEntityPacket movement) {
            if (!movement.hasPosition()
                    || !(movement.getEntity(client.level) instanceof Player player)
                    || player == client.player) return;
            TrackedPlayer tracked = track(player);
            if (movement.isOnGround() && movement.getYa() != 0) {
                tracked.invalidGroundVl = Math.min(50, tracked.invalidGroundVl + 1);
            } else if (!movement.isOnGround()) {
                tracked.invalidGroundVl /= 2;
            } else {
                tracked.invalidGroundVl = Math.max(0, tracked.invalidGroundVl - 1);
            }
        } else if (packet instanceof ClientboundRemoveEntitiesPacket remove) {
            remove.getEntityIds().forEach((int id) -> trackedPlayers.remove(id));
        }
    }

    boolean isBot(Minecraft client, Player player) {
        synchronizeLevel(client);
        if (client == null || client.getConnection() == null) return false;
        TrackedPlayer tracked = trackedPlayers.get(player.getId());
        return tracked != null && tracked.player == player && tracked.detector.isBot();
    }

    void reset() {
        trackedPlayers.clear();
    }

    private void synchronizeLevel(Minecraft client) {
        Object currentLevel = client == null ? null : client.level;
        Object currentConnection = client == null ? null : client.getConnection();
        Object currentPlayer = client == null ? null : client.player;
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

    private TrackedPlayer track(Player player) {
        TrackedPlayer tracked = trackedPlayers.get(player.getId());
        if (tracked == null || tracked.player != player) {
            tracked = new TrackedPlayer(player);
            trackedPlayers.put(player.getId(), tracked);
        }
        return tracked;
    }

    private static final class TrackedPlayer {
        private final Player player;
        private final AdvancedBotDetector detector = new AdvancedBotDetector();
        private int invalidGroundVl;
        private int evidenceTicks;

        private TrackedPlayer(Player player) {
            this.player = player;
        }
    }
}
