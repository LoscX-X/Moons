package com.blanoir.moons.client.module.impl.render;

import com.blanoir.moons.client.config.settings.BooleanSetting;
import com.blanoir.moons.client.config.settings.ModeSetting;
import com.blanoir.moons.client.config.settings.StringSetting;
import com.blanoir.moons.client.event.EventBus;
import com.blanoir.moons.client.ui.render.LegacyGuiGraphics;
import com.blanoir.moons.client.utils.text.DynamicMiniMessage;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.network.Packet;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.IChatComponent;

import java.util.ArrayList;
import java.util.List;

/** Presentation-only sidebar adapter; server score data remains authoritative. */
public final class Scoreboard {
    private enum Mode {
        LAST_LINE,
        ADD_LAST_LINE,
        CUSTOM
    }

    private static final BooleanSetting ENABLED =
            new BooleanSetting.Builder()
                    .name("scoreboardchanger.enabled")
                    .defaultValue(false)
                    .build();

    private static final ModeSetting<Mode> MODE =
            new ModeSetting.Builder<Mode>()
                    .name("scoreboardchanger.mode")
                    .defaultValue(Mode.LAST_LINE)
                    .option(Mode.LAST_LINE, "last_line")
                    .option(Mode.ADD_LAST_LINE, "add_last_line")
                    .option(Mode.CUSTOM, "custom")
                    .build();

    private static final StringSetting LAST_LINE =
            new StringSetting.Builder()
                    .name("scoreboardchanger.lastLine")
                    .defaultValue("<wave:#55c8ff:#b675ff:0.5>Moons Client</wave>")
                    .build();

    private static final StringSetting CUSTOM_LINES =
            new StringSetting.Builder()
                    .name("scoreboardchanger.customLines")
                    .defaultValue(
                            "<#55c8ff>Moons Client</#55c8ff>|<wave:#55c8ff:#b675ff:0.5>play.example.net</wave>")
                    .build();

    private static final BooleanSetting REPLACE_TITLE =
            new BooleanSetting.Builder()
                    .name("scoreboardchanger.replaceTitle")
                    .defaultValue(false)
                    .build();

    private static final StringSetting TITLE =
            new StringSetting.Builder()
                    .name("scoreboardchanger.title")
                    .defaultValue("<gradient:#55c8ff:#b675ff>Moons</gradient>")
                    .build();

    private static final BooleanSetting SHOW_SCORES =
            new BooleanSetting.Builder()
                    .name("scoreboardchanger.showScores")
                    .defaultValue(true)
                    .build();

    private static boolean initialized, renderedSinceHud;

    private Scoreboard() {}

    public static void initPacketListeners() {
        EventBus.PACKET_RECEIVE_APPLY.register(
                "Scoreboard.packetApply", e -> onScoreboardPacketApplied(e.packet()));
    }

    public static void onScoreboardPacketApplied(Packet<?> packet) {
        // Re-evaluate on the next HUD boundary after vanilla has updated objectives and teams.
        renderedSinceHud = false;
    }

    public static void init() {
        if (initialized) return;
        initialized = true;
        EventBus.HUD_RENDER.register(
                "Scoreboard.hudRender",
                e -> {
                    if (!renderedSinceHud && ENABLED.get())
                        render(e.graphics(), currentObjective());
                    renderedSinceHud = false;
                });
    }

    public static boolean render(LegacyGuiGraphics graphics, ScoreObjective objective) {
        if (!ENABLED.get() || graphics == null || objective == null) return false;
        long time = System.currentTimeMillis();
        IChatComponent title =
                REPLACE_TITLE.get()
                        ? DynamicMiniMessage.parse(TITLE.get(), time)
                        : new ChatComponentText(objective.getDisplayName());
        List<Line> lines = MODE.get() == Mode.CUSTOM ? customLines(time) : originalLines(objective);
        if (MODE.get() == Mode.LAST_LINE && !lines.isEmpty()) {
            int last = lines.size() - 1;
            lines.set(
                    last,
                    new Line(
                            DynamicMiniMessage.parse(LAST_LINE.get(), time),
                            lines.get(last).score()));
        } else if (MODE.get() == Mode.ADD_LAST_LINE) {
            lines.add(
                    new Line(
                            DynamicMiniMessage.parse(LAST_LINE.get(), time),
                            new ChatComponentText("")));
        }
        draw(graphics, Minecraft.getMinecraft().fontRendererObj, title, lines);
        renderedSinceHud = true;
        return true;
    }

    private static ScoreObjective currentObjective() {
        var client = Minecraft.getMinecraft();
        if (client.theWorld == null || client.thePlayer == null) return null;
        var board = client.theWorld.getScoreboard();
        var team = board.getPlayersTeam(client.thePlayer.getName());
        if (team != null && team.getChatFormat().getColorIndex() >= 0) {
            var objective =
                    board.getObjectiveInDisplaySlot(3 + team.getChatFormat().getColorIndex());
            if (objective != null) return objective;
        }
        return board.getObjectiveInDisplaySlot(1);
    }

    private static List<Line> originalLines(ScoreObjective objective) {
        var board = objective.getScoreboard();
        var scores = new ArrayList<>(board.getSortedScores(objective));
        scores.removeIf(
                score -> score.getPlayerName() == null || score.getPlayerName().startsWith("#"));
        var result = new ArrayList<Line>();
        for (int index = scores.size() - 1; index >= Math.max(0, scores.size() - 15); index--) {
            var score = scores.get(index);
            String name =
                    ScorePlayerTeam.formatPlayerName(
                            board.getPlayersTeam(score.getPlayerName()), score.getPlayerName());
            result.add(
                    new Line(
                            new ChatComponentText(name),
                            new ChatComponentText(
                                    SHOW_SCORES.get() ? "§c" + score.getScorePoints() : "")));
        }
        return result;
    }

    private static List<Line> customLines(long animationTime) {
        List<Line> lines = new ArrayList<>();
        for (String value : splitLines(CUSTOM_LINES.get())) {
            if (!value.isBlank()) {
                lines.add(
                        new Line(
                                DynamicMiniMessage.parse(value.trim(), animationTime),
                                new ChatComponentText("")));
            }
            if (lines.size() == 15) break;
        }
        return lines;
    }

    private static List<String> splitLines(String value) {
        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean escaped = false;
        String normalized = value == null ? "" : value.replace("\\n", "\n");
        for (int offset = 0; offset < normalized.length(); ) {
            int codePoint = normalized.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (escaped) {
                current.appendCodePoint(codePoint);
                escaped = false;
            } else if (codePoint == '\\') {
                escaped = true;
            } else if (codePoint == '|' || codePoint == '\n' || codePoint == '\r') {
                if (codePoint != '\r'
                        || offset >= normalized.length()
                        || normalized.codePointAt(offset) != '\n') {
                    lines.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.appendCodePoint(codePoint);
            }
        }
        if (escaped) current.append('\\');
        lines.add(current.toString());
        return lines;
    }

    private static void draw(
            LegacyGuiGraphics graphics, FontRenderer font, IChatComponent title, List<Line> lines) {
        int width = font.getStringWidth(title.getFormattedText());
        int colonWidth = font.getStringWidth(":");
        for (Line line : lines) {
            int scoreWidth = font.getStringWidth(line.score().getFormattedText());
            int lineWidth =
                    font.getStringWidth(line.name().getFormattedText())
                            + (scoreWidth > 0 ? colonWidth + scoreWidth : 0);
            width = Math.max(width, lineWidth);
        }

        int lineCount = lines.size();
        int height = lineCount * 9;
        int bottom = graphics.guiHeight() / 2 + height / 3;
        int top = bottom - height;
        int left = graphics.guiWidth() - width - 3;
        int right = graphics.guiWidth() - 1;
        int bodyColor = 0x4C000000;
        int titleColor = 0x66000000;

        graphics.fill(left - 2, top - 10, right, top - 1, titleColor);
        if (lineCount > 0) graphics.fill(left - 2, top - 1, right, bottom, bodyColor);
        graphics.text(
                font,
                title,
                left + width / 2 - font.getStringWidth(title.getFormattedText()) / 2,
                top - 9,
                0xFFFFFFFF,
                false);

        for (int index = 0; index < lineCount; index++) {
            Line line = lines.get(index);
            int y = top + index * 9;
            graphics.text(font, line.name(), left, y, 0xFFFFFFFF, false);
            int scoreWidth = font.getStringWidth(line.score().getFormattedText());
            if (scoreWidth > 0) {
                graphics.text(font, line.score(), right - scoreWidth, y, 0xFFFFFFFF, false);
            }
        }
    }

    public static boolean isEnabled() {
        return ENABLED.get();
    }

    public static int setEnabled(Minecraft ignoredClient, boolean value) {
        ENABLED.set(value);
        renderedSinceHud = false;
        return 1;
    }

    public static List<String> modeOptions() {
        return MODE.optionIds();
    }

    public static String modeText() {
        return MODE.serialized();
    }

    public static int setMode(Minecraft ignoredClient, String value) {
        MODE.deserialize(value);
        renderedSinceHud = false;
        return 1;
    }

    public static boolean customMode() {
        return MODE.get() == Mode.CUSTOM;
    }

    public static int setLastLine(Minecraft ignoredClient, String value) {
        LAST_LINE.set(sanitize(value, 256));
        renderedSinceHud = false;
        return 1;
    }

    public static int setCustomLines(Minecraft ignoredClient, String value) {
        CUSTOM_LINES.set(sanitize(value, 2048));
        return 1;
    }

    public static int setReplaceTitle(Minecraft ignoredClient, boolean value) {
        REPLACE_TITLE.set(value);
        renderedSinceHud = false;
        return 1;
    }

    public static int setTitle(Minecraft ignoredClient, String value) {
        TITLE.set(sanitize(value, 256));
        renderedSinceHud = false;
        return 1;
    }

    public static int setShowScores(Minecraft ignoredClient, boolean value) {
        SHOW_SCORES.set(value);
        return 1;
    }

    private static String sanitize(String value, int maximumLength) {
        String normalized = value == null ? "" : value.replaceAll("[\\p{Cntrl}&&[^\\r\\n]]", "");
        return normalized.length() <= maximumLength
                ? normalized
                : normalized.substring(0, maximumLength);
    }

    private record Line(IChatComponent name, IChatComponent score) {}
}
