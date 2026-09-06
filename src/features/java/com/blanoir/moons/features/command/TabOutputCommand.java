package com.blanoir.moons.features.command;

import com.blanoir.moons.client.access.GameAccess;
import com.blanoir.moons.client.access.MinecraftClientAccess;
import com.blanoir.moons.client.config.ClientBranding;
import com.blanoir.moons.client.chat.ClientChat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.world.scores.PlayerTeam;

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
    private TabOutputCommand() { }

    static boolean handle(String tail) {
        Minecraft client = Minecraft.getInstance();
        var connectionSnapshot = client == null ? null : client.getConnection();
        if (!tail.isBlank()) {
            ClientChat.send(client, "Usage: .taboutput");
            return true;
        }
        if (connectionSnapshot == null || client.gui == null) {
            ClientChat.send(client, "Tab output requires an active server connection.");
            return true;
        }
        PlayerTabOverlay overlay = MinecraftClientAccess.tabList(client);
        var entries = connectionSnapshot.getListedOnlinePlayers().stream()
                .sorted(Comparator.comparing(info -> info.getProfile().name(),
                        String.CASE_INSENSITIVE_ORDER)).toList();
        StringBuilder report = new StringBuilder(4096);
        report.append(ClientBranding.name()).append(" TAB output\n")
                .append("captured_at=").append(Instant.now()).append('\n')
                .append("player_count=").append(entries.size()).append('\n')
                .append("note=Server placeholders are already resolved by the server.\n\n");
        appendComponent(report, "header", GameAccess.tabHeader(overlay));
        appendComponent(report, "footer", GameAccess.tabFooter(overlay));
        report.append("\n[player_entries]\n");
        for (PlayerInfo info : entries) {
            report.append("\nprofile_name=").append(info.getProfile().name()).append('\n')
                    .append("uuid=").append(info.getProfile().id()).append('\n')
                    .append("latency=").append(info.getLatency()).append('\n')
                    .append("game_mode=").append(info.getGameMode()).append('\n');
            appendComponent(report, "server_tab_name", info.getTabListDisplayName());
            appendComponent(report, "final_local_name", overlay.getNameForDisplay(info));
            PlayerTeam team = info.getTeam();
            report.append("team=").append(team == null ? "<none>" : team.getName()).append('\n');
            if (team != null) {
                report.append("team_color=").append(MinecraftClientAccess.teamColorName(team)).append('\n');
                appendComponent(report, "team_prefix", team.getPlayerPrefix());
                appendComponent(report, "team_suffix", team.getPlayerSuffix());
            }
        }
        String configuredHome = System.getProperty("moons.home", "").trim();
        Path home = configuredHome.isEmpty()
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

    private static void appendComponent(StringBuilder output, String name, Component component) {
        if (component == null) {
            output.append(name).append("=<null>\n");
            return;
        }
        output.append(name).append(".plain=").append(escape(component.getString())).append('\n')
                .append(name).append(".tree=").append(escape(component.toString())).append('\n');
        Map<GlyphKey, Integer> glyphs = new LinkedHashMap<>();
        for (Component part : component.toFlatList()) {
            String font = fontName(part.getStyle().getFont());
            part.getString().codePoints()
                    .filter(codePoint -> Character.getType(codePoint) == Character.PRIVATE_USE)
                    .forEach(codePoint -> glyphs.merge(new GlyphKey(codePoint, font), 1, Integer::sum));
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
                    .append(" font=").append(entry.getKey().font())
                    .append(" count=").append(entry.getValue());
        }
        output.append('\n');
    }

    private static String fontName(FontDescription font) {
        return font instanceof FontDescription.Resource resource
                ? resource.id().toString() : font == null ? "minecraft:default" : font.toString();
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\r", "\\r").replace("\n", "\\n");
    }

    private record GlyphKey(int codePoint, String font) { }
}
