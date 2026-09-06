package com.blanoir.moons.client.utils.player;

import com.mojang.authlib.GameProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.world.entity.player.Player;

import java.util.Collection;
import java.util.Objects;
import java.util.UUID;

/** Player-list/profile queries shared by bot detection and target filtering. */
public final class PlayerListUtils {
    private PlayerListUtils() {
    }

    public static boolean isOnline(Minecraft client, UUID profileId) {
        return onlinePlayers(client).stream()
                .anyMatch(info -> Objects.equals(info.getProfile().id(), profileId));
    }

    public static boolean isListed(Minecraft client, UUID profileId) {
        var connectionSnapshot = client == null ? null : client.getConnection();
        if (client == null || connectionSnapshot == null || profileId == null) return false;
        return connectionSnapshot.getListedOnlinePlayers().stream()
                .anyMatch(info -> Objects.equals(info.getProfile().id(), profileId));
    }

    public static boolean hasSingleDifferentIdDuplicate(Minecraft client, GameProfile profile) {
        if (profile == null) return false;
        return onlinePlayers(client).stream()
                .map(PlayerInfo::getProfile)
                .filter(candidate -> Objects.equals(candidate.name(), profile.name())
                        && !Objects.equals(candidate.id(), profile.id()))
                .count() == 1L;
    }

    public static boolean isUniqueExactProfile(Minecraft client, GameProfile profile) {
        if (profile == null) return false;
        return onlinePlayers(client).stream()
                .map(PlayerInfo::getProfile)
                .filter(candidate -> Objects.equals(candidate.name(), profile.name())
                        && Objects.equals(candidate.id(), profile.id()))
                .count() == 1L;
    }

    public static boolean missingGameMode(Minecraft client, Player player) {
        var connectionSnapshot = client == null ? null : client.getConnection();
        if (client == null || connectionSnapshot == null || player == null) return true;
        PlayerInfo info = connectionSnapshot.getPlayerInfo(player.getUUID());
        return info == null || info.getGameMode() == null;
    }

    private static Collection<PlayerInfo> onlinePlayers(Minecraft client) {
        var connectionSnapshot = client == null ? null : client.getConnection();
        return client == null || connectionSnapshot == null
                ? java.util.List.of() : connectionSnapshot.getOnlinePlayers();
    }
}
