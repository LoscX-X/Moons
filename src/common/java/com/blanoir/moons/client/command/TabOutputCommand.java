package com.blanoir.moons.client.command;

import com.blanoir.moons.client.access.GameAccess;
import com.blanoir.moons.client.access.MinecraftClientAccess;
import com.blanoir.moons.client.chat.ClientChat;
import com.blanoir.moons.client.config.ClientBranding;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiPlayerTabOverlay;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.util.IChatComponent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;

/** Implements the local `.taboutput` command. */
final class TabOutputCommand {
    private TabOutputCommand() {}

    static boolean handle(String tail) {
        Minecraft client = Minecraft.getMinecraft();
        var connectionSnapshot = client == null ? null : client.getNetHandler();
        if (!tail.isBlank()) {
            ClientChat.send(client, "Usage: .taboutput");
            return true;
        }
        if (connectionSnapshot == null || client.ingameGUI == null) {
            ClientChat.send(client, "Tab output requires an active server connection.");
            return true;
        }
        GuiPlayerTabOverlay overlay = MinecraftClientAccess.tabList(client);
        var entries =
                connectionSnapshot.getPlayerInfoMap().stream()
                        .sorted(
                                Comparator.comparing(
                                        info -> info.getGameProfile().getName(),
                                        String.CASE_INSENSITIVE_ORDER))
                        .toList();
        StringBuilder report = new StringBuilder(4096);
        report.append(ClientBranding.name())
                .append(" TAB output\n")
                .append("captured_at=")
                .append(Instant.now())
                .append('\n')
                .append("player_count=")
                .append(entries.size())
                .append('\n')
                .append("note=Server placeholders are already resolved by the server.\n\n");
        appendComponent(report, "header", GameAccess.tabHeader(overlay));
        appendComponent(report, "footer", GameAccess.tabFooter(overlay));
        report.append("\n[player_entries]\n");
        for (NetworkPlayerInfo info : entries) {
            report.append("\nprofile_name=")
                    .append(info.getGameProfile().getName())
                    .append('\n')
                    .append("uuid=")
                    .append(info.getGameProfile().getId())
                    .append('\n')
                    .append("latency=")
                    .append(info.getResponseTime())
                    .append('\n')
                    .append("game_mode=")
                    .append(info.getGameType())
                    .append('\n');
            appendComponent(report, "server_tab_name", info.getDisplayName());
            appendComponent(
                    report,
                    "final_local_name",
                    new net.minecraft.util.ChatComponentText(overlay.getPlayerName(info)));
            ScorePlayerTeam team = info.getPlayerTeam();
            report.append("team=")
                    .append(team == null ? "<none>" : team.getRegisteredName())
                    .append('\n');
            if (team != null) {
                report.append("team_color=")
                        .append(MinecraftClientAccess.teamColorName(team))
                        .append('\n');
                appendComponent(
                        report,
                        "team_prefix",
                        new net.minecraft.util.ChatComponentText(team.getColorPrefix()));
                appendComponent(
                        report,
                        "team_suffix",
                        new net.minecraft.util.ChatComponentText(team.getColorSuffix()));
            }
        }
        String configuredHome = System.getProperty("moons.home", "").trim();
        Path home =
                configuredHome.isEmpty()
                        ? defaultHome()
                        : Path.of(configuredHome).toAbsolutePath().normalize();
        Path output = home.resolve("tab-output.txt").toAbsolutePath().normalize();
        try {
            Files.createDirectories(output.getParent());
            Files.writeString(output, report.toString(), StandardCharsets.UTF_8);
            ClientChat.send(client, "TAB output saved to " + output + ".");
        } catch (IOException failure) {
            ClientChat.send(client, "Failed to write TAB output: " + failure.getMessage());
        }
        return true;
    }

    private static Path defaultHome() {
        String appData = System.getenv("APPDATA");
        if (appData != null && !appData.isBlank()) {
            return Path.of(appData).resolve(".moons").toAbsolutePath().normalize();
        }
        return Path.of(System.getProperty("user.home", "."))
                .resolve(".moons")
                .toAbsolutePath()
                .normalize();
    }

    private static void appendComponent(
            StringBuilder output, String name, IChatComponent component) {
        if (component == null) {
            output.append(name).append("=<null>\n");
            return;
        }
        output.append(name)
                .append(".plain=")
                .append(escape(component.getUnformattedText()))
                .append('\n')
                .append(name)
                .append(".tree=")
                .append(escape(component.toString()))
                .append('\n');
        Map<GlyphKey, Integer> glyphs = new LinkedHashMap<>();
        for (IChatComponent part : component) {
            String font = "minecraft:default";
            part.getUnformattedTextForChat()
                    .codePoints()
                    .filter(codePoint -> Character.getType(codePoint) == Character.PRIVATE_USE)
                    .forEach(
                            codePoint ->
                                    glyphs.merge(new GlyphKey(codePoint, font), 1, Integer::sum));
        }
        output.append(name).append(".image_glyphs=");
        if (glyphs.isEmpty()) {
            output.append("<none>\n");
            return;
        }
        boolean first = true;
        for (Map.Entry<GlyphKey, Integer> entry : glyphs.entrySet()) {
            if (!first) output.append(", ");
            first = false;
            output.append(String.format("U+%04X", entry.getKey().codePoint()))
                    .append(" font=")
                    .append(entry.getKey().font())
                    .append(" count=")
                    .append(entry.getValue());
        }
        output.append('\n');
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\r", "\\r").replace("\n", "\\n");
    }

    private record GlyphKey(int codePoint, String font) {}
}
