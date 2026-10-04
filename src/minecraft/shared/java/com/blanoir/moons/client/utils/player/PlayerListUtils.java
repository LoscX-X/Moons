package com.blanoir.moons.client.utils.player;

import com.mojang.authlib.GameProfile;

import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.entity.player.EntityPlayer;

import java.util.Collection;
import java.util.Objects;
import java.util.UUID;

/** Player-list/profile queries shared by bot detection and target filtering. */
public final class PlayerListUtils {
    private PlayerListUtils() {}

    public static boolean isOnline(Minecraft client, UUID profileId) {
        return onlinePlayers(client).stream()
                .anyMatch(info -> Objects.equals(info.getGameProfile().getId(), profileId));
    }

    public static boolean isListed(Minecraft client, UUID profileId) {
        var connectionSnapshot = client == null ? null : client.getNetHandler();
        if (client == null || connectionSnapshot == null || profileId == null) return false;
        return connectionSnapshot.getPlayerInfoMap().stream()
                .anyMatch(info -> Objects.equals(info.getGameProfile().getId(), profileId));
    }

    public static boolean hasSingleDifferentIdDuplicate(Minecraft client, GameProfile profile) {
        if (profile == null) return false;
        return onlinePlayers(client).stream()
                        .map(NetworkPlayerInfo::getGameProfile)
                        .filter(
                                candidate ->
                                        Objects.equals(candidate.getName(), profile.getName())
                                                && !Objects.equals(
                                                        candidate.getId(), profile.getId()))
                        .count()
                == 1L;
    }

    public static boolean isUniqueExactProfile(Minecraft client, GameProfile profile) {
        if (profile == null) return false;
        return onlinePlayers(client).stream()
                        .map(NetworkPlayerInfo::getGameProfile)
                        .filter(
                                candidate ->
                                        Objects.equals(candidate.getName(), profile.getName())
                                                && Objects.equals(
                                                        candidate.getId(), profile.getId()))
                        .count()
                == 1L;
    }

    public static boolean missingGameMode(Minecraft client, EntityPlayer player) {
        var connectionSnapshot = client == null ? null : client.getNetHandler();
        if (client == null || connectionSnapshot == null || player == null) return true;
        NetworkPlayerInfo info = connectionSnapshot.getPlayerInfo(player.getUniqueID());
        return info == null;
    }

    private static Collection<NetworkPlayerInfo> onlinePlayers(Minecraft client) {
        var connectionSnapshot = client == null ? null : client.getNetHandler();
        return client == null || connectionSnapshot == null
                ? java.util.List.of()
                : connectionSnapshot.getPlayerInfoMap();
    }
}
