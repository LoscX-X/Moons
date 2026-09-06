package com.blanoir.moons.client.utils.player;

import com.blanoir.moons.client.access.MinecraftClientAccess;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.ReadOnlyScoreInfo;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Resolves server-authoritative player health when it is exposed in the TAB list. */
public final class PlayerHealthResolver {
    private static final Pattern NUMBER = Pattern.compile("(?<![A-Za-z0-9_])([0-9]+(?:\\.[0-9]+)?)(?![A-Za-z0-9_])");
    private static final Pattern HEALTH_WORD = Pattern.compile("(?i)(?:health|hp|heart|hearts|❤|♥)");

    private PlayerHealthResolver() {
    }

    public static float resolve(Player player) {
        if (player == null) {
            return 0.0F;
        }

        Minecraft client = Minecraft.getInstance();
        Float scoreboardHealth = tabScoreboardHealth(client, player);
        if (scoreboardHealth != null) {
            return scoreboardHealth;
        }

        Float suffixHealth = tabSuffixHealth(client, player);
        if (suffixHealth != null) {
            return suffixHealth;
        }

        float health = player.getHealth();
        return Float.isFinite(health) ? Math.max(0.0F, health) : 0.0F;
    }

    public static float max(Player player) {
        if (player == null) {
            return 20.0F;
        }
        float max = player.getMaxHealth();
        return Float.isFinite(max) && max > 0.0F ? max : 20.0F;
    }

    private static Float tabScoreboardHealth(Minecraft client, Player player) {
        var currentLevel = client == null ? null : client.level;
        if (client == null || currentLevel == null) {
            return null;
        }

        Scoreboard scoreboard = currentLevel.getScoreboard();
        Objective objective = scoreboard.getDisplayObjective(DisplaySlot.LIST);
        if (objective == null || !isHealthObjective(client, objective)) {
            return null;
        }

        ReadOnlyScoreInfo score = scoreboard.getPlayerScoreInfo(player, objective);
        return score == null ? null : validHealth(score.value(), player, false);
    }

    private static boolean isHealthObjective(Minecraft client, Objective objective) {
        if (objective.getRenderType() == ObjectiveCriteria.RenderType.HEARTS) {
            return true;
        }
        String id = objective.getName();
        String display = objective.getDisplayName() == null ? "" : objective.getDisplayName().getString();
        return HEALTH_WORD.matcher(id + " " + display).find() || isHoplite(client);
    }

    private static boolean isHoplite(Minecraft client) {
        var server = client == null ? null : client.getCurrentServer();
        if (server == null || server.ip == null) {
            return false;
        }
        return server.ip.toLowerCase(Locale.ROOT).contains("hoplite");
    }

    private static Float tabSuffixHealth(Minecraft client, Player player) {
        var connectionSnapshot = client == null ? null : client.getConnection();
        if (client == null || connectionSnapshot == null) {
            return null;
        }

        PlayerInfo info = connectionSnapshot.getPlayerInfo(player.getUUID());
        if (info == null) {
            return null;
        }

        String profileName = info.getProfile().name();
        Float health = numberAfterName(
                MinecraftClientAccess.tabList(client).getNameForDisplay(info), profileName, player);
        if (health != null) {
            return health;
        }

        health = numberAfterName(info.getTabListDisplayName(), profileName, player);
        if (health != null) {
            return health;
        }

        var team = info.getTeam();
        return team == null
                ? null
                : firstValidNumber(team.getPlayerSuffix().getString(), player);
    }

    private static Float numberAfterName(Component component, String profileName, Player player) {
        if (component == null || profileName == null || profileName.isBlank()) {
            return null;
        }
        String visible = strip(component.getString());
        int nameIndex = visible.toLowerCase(Locale.ROOT).indexOf(profileName.toLowerCase(Locale.ROOT));
        if (nameIndex < 0) {
            return null;
        }
        return firstValidNumber(visible.substring(nameIndex + profileName.length()), player);
    }

    private static Float firstValidNumber(String text, Player player) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Matcher matcher = NUMBER.matcher(strip(text));
        while (matcher.find()) {
            try {
                Float health = validHealth(Float.parseFloat(matcher.group(1)), player, true);
                if (health != null) {
                    return health;
                }
            } catch (NumberFormatException ignored) {
                // Keep searching when a server emits a malformed component.
            }
        }
        return null;
    }

    private static Float validHealth(float value, Player player, boolean strict) {
        if (!Float.isFinite(value) || value < 0.0F) {
            return null;
        }
        float max = max(player);
        float upperBound = strict ? Math.max(100.0F, max * 5.0F) : Math.max(2048.0F, max * 100.0F);
        return value <= upperBound ? value : null;
    }

    private static String strip(String text) {
        String stripped = ChatFormatting.stripFormatting(text);
        return stripped == null ? text : stripped;
    }
}
