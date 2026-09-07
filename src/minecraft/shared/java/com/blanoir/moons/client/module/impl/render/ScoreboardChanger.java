package com.blanoir.moons.client.module.impl.render;

import com.blanoir.moons.client.access.MinecraftClientAccess;
import com.blanoir.moons.client.config.settings.BooleanSetting;
import com.blanoir.moons.client.config.settings.ModeSetting;
import com.blanoir.moons.client.config.settings.StringSetting;
import com.blanoir.moons.client.event.EventBus;
import com.blanoir.moons.client.utils.text.DynamicMiniMessage;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.numbers.BlankFormat;
import net.minecraft.network.chat.numbers.NumberFormat;
import net.minecraft.network.chat.numbers.StyledFormat;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundResetScorePacket;
import net.minecraft.network.protocol.game.ClientboundSetDisplayObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSetObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket;
import net.minecraft.network.protocol.game.ClientboundSetScorePacket;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.ScoreAccess;
import net.minecraft.world.scores.ScoreHolder;
import net.minecraft.world.scores.Scoreboard;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class ScoreboardChanger {
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

    private static boolean initialized;
    private static boolean renderedSinceHud;
    private static Objective dataObjective;
    private static Component originalTitle;
    private static boolean titlePatched;
    private static String lineOwner;
    private static Component originalLineDisplay;
    private static PlayerTeam originalLineTeam;
    private static boolean linePatched;
    private static final String ADDED_LINE_OWNER = "§0§1§2§3§4§5§r";
    private static Objective addedLineObjective;
    private static boolean addedLine;

    private ScoreboardChanger() {}

    public static void initPacketListeners() {
        EventBus.PACKET_RECEIVE_APPLY.register(
                "ScoreboardChanger.packetApply",
                event -> onScoreboardPacketApplied(event.packet()));
    }

    public static void init() {
        if (initialized) return;
        initialized = true;
        EventBus.HUD_RENDER.register(
                "ScoreboardChanger.hudRender", event -> renderFallback(event.graphics()));
    }

    public static boolean render(GuiGraphicsExtractor graphics, Objective objective) {
        syncClientData();
        if (!ENABLED.get() || graphics == null || objective == null) return false;

        Minecraft client = Minecraft.getInstance();
        Font font = client.font;
        long animationTime = System.currentTimeMillis();
        Component title =
                REPLACE_TITLE.get()
                        ? DynamicMiniMessage.parse(TITLE.get(), animationTime)
                        : objective.getDisplayName();

        List<Line> lines =
                MODE.get() == Mode.CUSTOM ? customLines(animationTime) : originalLines(objective);

        if (MODE.get() == Mode.LAST_LINE && !lines.isEmpty()) {
            int last = lines.size() - 1;
            Line original = lines.get(last);
            lines.set(
                    last,
                    new Line(
                            DynamicMiniMessage.parse(LAST_LINE.get(), animationTime),
                            original.score()));
        } else if (MODE.get() == Mode.ADD_LAST_LINE && !addedLine) {
            // A host-owned sidebar is capped at 15 entries. The direct
            // renderer can still show the requested appended line when the
            // server already occupies every native slot.
            lines.add(
                    new Line(
                            DynamicMiniMessage.parse(LAST_LINE.get(), animationTime),
                            Component.empty()));
        }
        draw(graphics, font, title, lines);
        renderedSinceHud = true;
        return true;
    }

    private static void renderFallback(GuiGraphicsExtractor graphics) {
        syncClientData();
        if (!ENABLED.get()) {
            renderedSinceHud = false;
            return;
        }
        if (renderedSinceHud) {
            renderedSinceHud = false;
            return;
        }
        // LastLine and title replacements are already present in the client
        // Scoreboard. This lets a host-owned HUD render them
        // without drawing a second sidebar over the top.
        if (MODE.get() != Mode.CUSTOM) return;
        Objective objective = currentObjective();
        if (objective == null) return;
        graphics.nextStratum();
        render(graphics, objective);
        renderedSinceHud = false;
    }

    private static Objective currentObjective() {
        Minecraft client = Minecraft.getInstance();
        var currentPlayer = client == null ? null : client.player;
        var currentLevel = client == null ? null : client.level;
        if (currentLevel == null || currentPlayer == null) return null;
        Scoreboard scoreboard = currentLevel.getScoreboard();
        PlayerTeam team = scoreboard.getPlayersTeam(currentPlayer.getScoreboardName());
        if (team != null) {
            DisplaySlot teamSlot = MinecraftClientAccess.teamDisplaySlot(team);
            if (teamSlot != null) {
                Objective teamObjective = scoreboard.getDisplayObjective(teamSlot);
                if (teamObjective != null) return teamObjective;
            }
        }
        return scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR);
    }

    private static List<Line> originalLines(Objective objective) {
        Scoreboard scoreboard = objective.getScoreboard();
        NumberFormat numberFormat = objective.numberFormatOrDefault(StyledFormat.SIDEBAR_DEFAULT);
        return new ArrayList<>(
                visibleEntries(objective).stream()
                        .map(
                                entry ->
                                        new Line(
                                                PlayerTeam.formatNameForTeam(
                                                        scoreboard.getPlayersTeam(entry.owner()),
                                                        entry.ownerName()),
                                                SHOW_SCORES.get()
                                                        ? entry.formatValue(numberFormat)
                                                        : Component.empty()))
                        .toList());
    }

    private static List<PlayerScoreEntry> visibleEntries(Objective objective) {
        return objective.getScoreboard().listPlayerScores(objective).stream()
                .filter(entry -> !entry.isHidden())
                .sorted(
                        Comparator.comparingInt(PlayerScoreEntry::value)
                                .reversed()
                                .thenComparing(
                                        PlayerScoreEntry::owner, String.CASE_INSENSITIVE_ORDER))
                .limit(15)
                .toList();
    }

    /**
     * Runs after vanilla applies scoreboard packets and from the HUD event.
     * Replacement mode changes only visual Components. AddLastLine creates one
     * reversible local-only score owner so host-owned sidebar renderers can
     * see the appended row without altering any server owner or score.
     */
    public static void onScoreboardPacketApplied(Packet<?> packet) {
        boolean scoreboardPacket = false;
        if (packet instanceof ClientboundSetScorePacket score) {
            scoreboardPacket = true;
            if (linePatched
                    && dataObjective != null
                    && dataObjective.getName().equals(score.objectiveName())
                    && lineOwner.equals(score.owner())) {
                // Vanilla has replaced our display with the new server value.
                // Keep the owner/team state and refresh the restore snapshot.
                originalLineDisplay =
                        findEntry(dataObjective, lineOwner)
                                .map(PlayerScoreEntry::display)
                                .orElse(null);
            }
        } else if (packet instanceof ClientboundResetScorePacket reset) {
            scoreboardPacket = true;
            if (linePatched
                    && dataObjective != null
                    && lineOwner.equals(reset.owner())
                    && (reset.objectiveName() == null
                            || dataObjective.getName().equals(reset.objectiveName()))) {
                restoreLineTeam();
                clearLineState();
            }
        } else if (packet instanceof ClientboundSetObjectivePacket objectivePacket) {
            scoreboardPacket = true;
            if (dataObjective != null
                    && dataObjective.getName().equals(objectivePacket.getObjectiveName())) {
                if (objectivePacket.getMethod() == ClientboundSetObjectivePacket.METHOD_REMOVE) {
                    clearDataState();
                } else {
                    // Vanilla has just installed the authoritative title.
                    titlePatched = false;
                    originalTitle = null;
                }
            }
        } else if (packet instanceof ClientboundSetPlayerTeamPacket teamPacket) {
            scoreboardPacket = true;
            refreshLineTeamAfterPacket(teamPacket);
        } else if (packet instanceof ClientboundSetDisplayObjectivePacket) {
            scoreboardPacket = true;
        }
        if (scoreboardPacket) syncClientData();
    }

    private static void syncClientData() {
        Objective objective = currentObjective();
        if (!ENABLED.get() || objective == null) {
            restoreData();
            return;
        }
        if (dataObjective != objective) {
            restoreData();
            dataObjective = objective;
        }

        long animationTime = System.currentTimeMillis();
        if (REPLACE_TITLE.get()) {
            if (!titlePatched) {
                originalTitle = objective.getDisplayName();
                titlePatched = true;
            }
            objective.setDisplayName(DynamicMiniMessage.parse(TITLE.get(), animationTime));
        } else {
            restoreTitle();
        }

        if (MODE.get() == Mode.ADD_LAST_LINE) {
            restoreLine();
            syncAddedLine(objective, animationTime);
            return;
        }
        restoreAddedLine();
        if (MODE.get() != Mode.LAST_LINE) {
            restoreLine();
            return;
        }
        List<PlayerScoreEntry> entries = visibleEntries(objective);
        if (entries.isEmpty()) {
            restoreLine();
            return;
        }
        PlayerScoreEntry bottom = entries.get(entries.size() - 1);
        if (linePatched && !lineOwner.equals(bottom.owner())) restoreLine();
        if (!linePatched) {
            lineOwner = bottom.owner();
            originalLineDisplay = bottom.display();
            originalLineTeam = objective.getScoreboard().getPlayersTeam(lineOwner);
            linePatched = true;
        }
        detachLineTeam();
        scoreAccess(objective, lineOwner)
                .display(DynamicMiniMessage.parse(LAST_LINE.get(), animationTime));
    }

    private static void syncAddedLine(Objective objective, long animationTime) {
        List<PlayerScoreEntry> serverEntries =
                objective.getScoreboard().listPlayerScores(objective).stream()
                        .filter(entry -> !entry.isHidden())
                        .filter(entry -> !entry.owner().equals(ADDED_LINE_OWNER))
                        .sorted(
                                Comparator.comparingInt(PlayerScoreEntry::value)
                                        .reversed()
                                        .thenComparing(
                                                PlayerScoreEntry::owner,
                                                String.CASE_INSENSITIVE_ORDER))
                        .toList();
        if (serverEntries.size() >= 15) {
            restoreAddedLine();
            return;
        }
        if (addedLineObjective != objective) {
            restoreAddedLine();
            addedLineObjective = objective;
        }
        int bottomScore =
                serverEntries.isEmpty() ? 0 : serverEntries.get(serverEntries.size() - 1).value();
        int addedScore = bottomScore == Integer.MIN_VALUE ? Integer.MIN_VALUE : bottomScore - 1;
        ScoreAccess access = scoreAccess(objective, ADDED_LINE_OWNER);
        if (!addedLine || access.get() != addedScore) access.set(addedScore);
        access.display(DynamicMiniMessage.parse(LAST_LINE.get(), animationTime));
        access.numberFormatOverride(BlankFormat.INSTANCE);
        addedLine = true;
    }

    private static void restoreAddedLine() {
        if (addedLine && addedLineObjective != null) {
            addedLineObjective
                    .getScoreboard()
                    .resetSinglePlayerScore(
                            ScoreHolder.forNameOnly(ADDED_LINE_OWNER), addedLineObjective);
        }
        addedLine = false;
        addedLineObjective = null;
    }

    private static java.util.Optional<PlayerScoreEntry> findEntry(
            Objective objective, String owner) {
        return objective.getScoreboard().listPlayerScores(objective).stream()
                .filter(entry -> entry.owner().equals(owner))
                .findFirst();
    }

    private static void refreshLineTeamAfterPacket(ClientboundSetPlayerTeamPacket packet) {
        if (!linePatched || dataObjective == null) return;
        if (packet.getTeamAction() == ClientboundSetPlayerTeamPacket.Action.REMOVE
                && originalLineTeam != null
                && originalLineTeam.getName().equals(packet.getName())) {
            originalLineTeam = null;
        }
        if (!packet.getPlayers().contains(lineOwner)) return;
        if (packet.getPlayerAction() == ClientboundSetPlayerTeamPacket.Action.REMOVE) {
            originalLineTeam = null;
        } else if (packet.getPlayerAction() == ClientboundSetPlayerTeamPacket.Action.ADD) {
            originalLineTeam = dataObjective.getScoreboard().getPlayerTeam(packet.getName());
        }
    }

    private static void detachLineTeam() {
        if (!linePatched || dataObjective == null) return;
        Scoreboard scoreboard = dataObjective.getScoreboard();
        PlayerTeam currentTeam = scoreboard.getPlayersTeam(lineOwner);
        if (currentTeam != null) {
            originalLineTeam = currentTeam;
            scoreboard.removePlayerFromTeam(lineOwner, currentTeam);
        }
    }

    private static void restoreLineTeam() {
        if (!linePatched || dataObjective == null || originalLineTeam == null) return;
        Scoreboard scoreboard = dataObjective.getScoreboard();
        if (scoreboard.getPlayersTeam(lineOwner) != null) return;
        PlayerTeam currentTeam = scoreboard.getPlayerTeam(originalLineTeam.getName());
        if (currentTeam != null) scoreboard.addPlayerToTeam(lineOwner, currentTeam);
    }

    private static ScoreAccess scoreAccess(Objective objective, String owner) {
        return objective
                .getScoreboard()
                .getOrCreatePlayerScore(ScoreHolder.forNameOnly(owner), objective, true);
    }

    private static void restoreData() {
        restoreLine();
        restoreAddedLine();
        restoreTitle();
        dataObjective = null;
    }

    private static void restoreLine() {
        if (linePatched && dataObjective != null) {
            boolean stillExists =
                    dataObjective.getScoreboard().listPlayerScores(dataObjective).stream()
                            .anyMatch(entry -> entry.owner().equals(lineOwner));
            if (stillExists) scoreAccess(dataObjective, lineOwner).display(originalLineDisplay);
            restoreLineTeam();
        }
        clearLineState();
    }

    private static void restoreTitle() {
        if (titlePatched && dataObjective != null) dataObjective.setDisplayName(originalTitle);
        titlePatched = false;
        originalTitle = null;
    }

    private static void clearLineState() {
        linePatched = false;
        lineOwner = null;
        originalLineDisplay = null;
        originalLineTeam = null;
    }

    private static void clearDataState() {
        restoreLineTeam();
        clearLineState();
        // The objective was already removed by vanilla, so only forget the
        // local synthetic row; there is no score left to reset.
        addedLine = false;
        addedLineObjective = null;
        titlePatched = false;
        originalTitle = null;
        dataObjective = null;
    }

    private static List<Line> customLines(long animationTime) {
        List<Line> lines = new ArrayList<>();
        for (String value : splitLines(CUSTOM_LINES.get())) {
            if (!value.isBlank()) {
                lines.add(
                        new Line(
                                DynamicMiniMessage.parse(value.trim(), animationTime),
                                Component.empty()));
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
            GuiGraphicsExtractor graphics, Font font, Component title, List<Line> lines) {
        int width = font.width(title);
        int colonWidth = font.width(":");
        for (Line line : lines) {
            int scoreWidth = font.width(line.score());
            int lineWidth =
                    font.width(line.name()) + (scoreWidth > 0 ? colonWidth + scoreWidth : 0);
            width = Math.max(width, lineWidth);
        }

        int lineCount = lines.size();
        int height = lineCount * 9;
        int bottom = graphics.guiHeight() / 2 + height / 3;
        int top = bottom - height;
        int left = graphics.guiWidth() - width - 3;
        int right = graphics.guiWidth() - 1;
        int bodyColor = Minecraft.getInstance().options.getBackgroundColor(0.3F);
        int titleColor = Minecraft.getInstance().options.getBackgroundColor(0.4F);

        graphics.fill(left - 2, top - 10, right, top - 1, titleColor);
        if (lineCount > 0) graphics.fill(left - 2, top - 1, right, bottom, bodyColor);
        graphics.text(
                font, title, left + width / 2 - font.width(title) / 2, top - 9, 0xFFFFFFFF, false);

        for (int index = 0; index < lineCount; index++) {
            Line line = lines.get(index);
            int y = top + index * 9;
            graphics.text(font, line.name(), left, y, 0xFFFFFFFF, false);
            int scoreWidth = font.width(line.score());
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
        syncClientData();
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
        syncClientData();
        return 1;
    }

    public static boolean customMode() {
        return MODE.get() == Mode.CUSTOM;
    }

    public static int setLastLine(Minecraft ignoredClient, String value) {
        LAST_LINE.set(sanitize(value, 256));
        syncClientData();
        return 1;
    }

    public static int setCustomLines(Minecraft ignoredClient, String value) {
        CUSTOM_LINES.set(sanitize(value, 2048));
        return 1;
    }

    public static int setReplaceTitle(Minecraft ignoredClient, boolean value) {
        REPLACE_TITLE.set(value);
        syncClientData();
        return 1;
    }

    public static int setTitle(Minecraft ignoredClient, String value) {
        TITLE.set(sanitize(value, 256));
        syncClientData();
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

    private record Line(Component name, Component score) {}
}
