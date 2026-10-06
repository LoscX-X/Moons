package com.blanoir.moons.client.utils.player;

import com.blanoir.moons.client.access.MinecraftClientAccess;

import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.scoreboard.IScoreObjectiveCriteria;
import net.minecraft.scoreboard.Score;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Resolves server-authoritative player health when it is exposed in the TAB list. */
public final class PlayerHealthResolver {
    private static final Pattern NUMBER =
            Pattern.compile("(?<![A-Za-z0-9_])([0-9]+(?:\\.[0-9]+)?)(?![A-Za-z0-9_])");
    private static final Pattern HEALTH_WORD =
            Pattern.compile("(?i)(?:health|hp|heart|hearts|❤|♥)");

    private PlayerHealthResolver() {}

    public static float resolve(EntityPlayer player) {
        if (player == null) {
            return 0.0F;
        }

        Minecraft client = Minecraft.getMinecraft();
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

    public static float max(EntityPlayer player) {
        if (player == null) {
            return 20.0F;
        }
        float max = player.getMaxHealth();
        return Float.isFinite(max) && max > 0.0F ? max : 20.0F;
    }

    private static Float tabScoreboardHealth(Minecraft client, EntityPlayer player) {
        var currentLevel = client == null ? null : client.theWorld;
        if (client == null || currentLevel == null) {
            return null;
        }

        Scoreboard scoreboard = currentLevel.getScoreboard();
        ScoreObjective objective = scoreboard.getObjectiveInDisplaySlot(0);
        if (objective == null || !isHealthObjective(client, objective)) {
            return null;
        }

        Score score =
                scoreboard.entityHasObjective(player.getName(), objective)
                        ? scoreboard.getValueFromObjective(player.getName(), objective)
                        : null;
        return score == null ? null : validHealth(score.getScorePoints(), player, false);
    }

    private static boolean isHealthObjective(Minecraft client, ScoreObjective objective) {
        if (objective.getRenderType() == IScoreObjectiveCriteria.EnumRenderType.HEARTS) {
            return true;
        }
        String id = objective.getName();
        String display = objective.getDisplayName();
        return HEALTH_WORD.matcher(id + " " + display).find() || isHoplite(client);
    }

    private static boolean isHoplite(Minecraft client) {
        var server = client == null ? null : client.getCurrentServerData();
        if (server == null) {
            return false;
        }
        return server.serverIP.toLowerCase(Locale.ROOT).contains("hoplite");
    }

    private static Float tabSuffixHealth(Minecraft client, EntityPlayer player) {
        var connectionSnapshot = client == null ? null : client.getNetHandler();
        if (client == null || connectionSnapshot == null) {
            return null;
        }

        NetworkPlayerInfo info = connectionSnapshot.getPlayerInfo(player.getUniqueID());
        if (info == null) {
            return null;
        }

        String profileName = info.getGameProfile().getName();
        Float health =
                numberAfterName(
                        new net.minecraft.util.ChatComponentText(
                                MinecraftClientAccess.tabList(client).getPlayerName(info)),
                        profileName,
                        player);
        if (health != null) {
            return health;
        }

        health = numberAfterName(info.getDisplayName(), profileName, player);
        if (health != null) {
            return health;
        }

        var team = info.getPlayerTeam();
        return team == null ? null : firstValidNumber(team.getColorSuffix(), player);
    }

    private static Float numberAfterName(
            IChatComponent component, String profileName, EntityPlayer player) {
        if (component == null || profileName == null || profileName.isBlank()) {
            return null;
        }
        String visible = strip(component.getUnformattedText());
        int nameIndex =
                visible.toLowerCase(Locale.ROOT).indexOf(profileName.toLowerCase(Locale.ROOT));
        if (nameIndex < 0) {
            return null;
        }
        return firstValidNumber(visible.substring(nameIndex + profileName.length()), player);
    }

    private static Float firstValidNumber(String text, EntityPlayer player) {
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

    private static Float validHealth(float value, EntityPlayer player, boolean strict) {
        if (!Float.isFinite(value) || value < 0.0F) {
            return null;
        }
        float max = max(player);
        float upperBound = strict ? Math.max(100.0F, max * 5.0F) : Math.max(2048.0F, max * 100.0F);
        return value <= upperBound ? value : null;
    }

    private static String strip(String text) {
        String stripped = EnumChatFormatting.getTextWithoutFormattingCodes(text);
        return stripped == null ? text : stripped;
    }
}
