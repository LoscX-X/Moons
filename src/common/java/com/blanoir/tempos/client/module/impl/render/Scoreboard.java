package com.blanoir.tempos.client.module.impl.render;


import com.blanoir.tempos.client.access.MinecraftClientAccess;
import com.blanoir.tempos.client.config.settings.BooleanSetting;
import com.blanoir.tempos.client.config.settings.ModeSetting;
import com.blanoir.tempos.client.config.settings.StringSetting;
import com.blanoir.tempos.client.event.EventBus;
import com.blanoir.tempos.client.module.framework.ClientFeature;
import com.blanoir.tempos.client.module.framework.ModuleCategory;
import com.blanoir.tempos.client.module.framework.ModuleInfo;
import com.blanoir.tempos.client.module.framework.ModuleModel.Setting;
import com.blanoir.tempos.client.utils.text.DynamicMiniMessage;

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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@ModuleInfo(
        category = ModuleCategory.RENDER,
        description = "Customizes the scoreboard title and displayed lines.")
public final class Scoreboard extends ClientFeature {
    public static final Scoreboard INSTANCE = new Scoreboard();

    private enum Mode {
        LAST_LINE,
        ADD_LAST_LINE,
        CUSTOM
    }

    private final BooleanSetting enabled =
            new BooleanSetting.Builder()
                    .name("scoreboardchanger.enabled")
                    .defaultValue(false)
                    .build();

    private final ModeSetting<Mode> mode =
            new ModeSetting.Builder<Mode>()
                    .name("scoreboardchanger.mode")
                    .defaultValue(Mode.LAST_LINE)
                    .option(Mode.LAST_LINE, "last_line")
                    .option(Mode.ADD_LAST_LINE, "add_last_line")
                    .option(Mode.CUSTOM, "custom")
                    .build();

    private final StringSetting lastLine =
            new StringSetting.Builder()
                    .name("scoreboardchanger.lastLine")
                    .defaultValue("<wave:#55c8ff:#b675ff:0.5>Tempos Client</wave>")
                    .build();

    private final StringSetting customLines =
            new StringSetting.Builder()
                    .name("scoreboardchanger.customLines")
                    .defaultValue(
                            "<#55c8ff>Tempos Client</#55c8ff>|<wave:#55c8ff:#b675ff:0.5>play.example.net</wave>")
                    .build();

    private final BooleanSetting replaceTitle =
            new BooleanSetting.Builder()
                    .name("scoreboardchanger.replaceTitle")
                    .defaultValue(false)
                    .build();

    private final StringSetting title =
            new StringSetting.Builder()
                    .name("scoreboardchanger.title")
                    .defaultValue("<gradient:#55c8ff:#b675ff>Tempos</gradient>")
                    .build();

    private final BooleanSetting showScores =
            new BooleanSetting.Builder()
                    .name("scoreboardchanger.showScores")
                    .defaultValue(true)
                    .build();

    private boolean initialized;
    private boolean renderedSinceHud;
    private Objective dataObjective;
    private Component originalTitle;
    private boolean titlePatched;
    private String lineOwner;
    private Component originalLineDisplay;
    private PlayerTeam originalLineTeam;
    private boolean linePatched;
    private static final String ADDED_LINE_OWNER = "§0§1§2§3§4§5§r";
    private Objective addedLineObjective;
    private boolean addedLine;

    private Scoreboard() {
        super("scoreboardchanger", "Scoreboard");
    }

    public void initPacketListeners() {
        EventBus.PACKET_RECEIVE_APPLY.register(
                "Scoreboard.packetApply", event -> onScoreboardPacketApplied(event.packet()));
    }

    @Override
    public void registerEvents() {
        if (this.initialized) return;
        this.initialized = true;
        EventBus.HUD_RENDER.register(
                "Scoreboard.hudRender", event -> renderFallback(event.graphics()));
    }

    public boolean render(GuiGraphicsExtractor graphics, Objective objective) {
        syncClientData();
        if (!this.enabled.get() || graphics == null || objective == null) return false;

        Minecraft client = Minecraft.getInstance();
        Font font = client.font;
        long animationTime = System.currentTimeMillis();
        Component title =
                this.replaceTitle.get()
                        ? DynamicMiniMessage.parse(this.title.get(), animationTime)
                        : objective.getDisplayName();

        List<Line> lines =
                this.mode.get() == Mode.CUSTOM
                        ? customLines(animationTime)
                        : originalLines(objective);

        if (this.mode.get() == Mode.LAST_LINE && !lines.isEmpty()) {
            int last = lines.size() - 1;
            Line original = lines.get(last);
            lines.set(
                    last,
                    new Line(
                            DynamicMiniMessage.parse(this.lastLine.get(), animationTime),
                            original.score()));
        } else if (this.mode.get() == Mode.ADD_LAST_LINE && !this.addedLine) {
            // A host-owned sidebar is capped at 15 entries. The direct
            // renderer can still show the requested appended line when the
            // server already occupies every native slot.
            lines.add(
                    new Line(
                            DynamicMiniMessage.parse(this.lastLine.get(), animationTime),
                            Component.empty()));
        }
        draw(graphics, font, title, lines);
        this.renderedSinceHud = true;
        return true;
    }

    private void renderFallback(GuiGraphicsExtractor graphics) {
        syncClientData();
        if (!this.enabled.get()) {
            this.renderedSinceHud = false;
            return;
        }
        if (this.renderedSinceHud) {
            this.renderedSinceHud = false;
            return;
        }
        // LastLine and title replacements are already present in the client
        // Scoreboard. This lets a host-owned HUD render them
        // without drawing a second sidebar over the top.
        if (this.mode.get() != Mode.CUSTOM) return;
        Objective objective = currentObjective();
        if (objective == null) return;
        graphics.nextStratum();
        render(graphics, objective);
        this.renderedSinceHud = false;
    }

    private static Objective currentObjective() {
        Minecraft client = Minecraft.getInstance();
        var currentPlayer = client == null ? null : client.player;
        var currentLevel = client == null ? null : client.level;
        if (currentLevel == null || currentPlayer == null) return null;
        net.minecraft.world.scores.Scoreboard scoreboard = currentLevel.getScoreboard();
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

    private List<Line> originalLines(Objective objective) {
        net.minecraft.world.scores.Scoreboard scoreboard = objective.getScoreboard();
        NumberFormat numberFormat = objective.numberFormatOrDefault(StyledFormat.SIDEBAR_DEFAULT);
        return new ArrayList<>(
                visibleEntries(objective).stream()
                        .map(
                                entry ->
                                        new Line(
                                                PlayerTeam.formatNameForTeam(
                                                        scoreboard.getPlayersTeam(entry.owner()),
                                                        entry.ownerName()),
                                                this.showScores.get()
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
    public void onScoreboardPacketApplied(Packet<?> packet) {
        boolean scoreboardPacket = false;
        if (packet instanceof ClientboundSetScorePacket score) {
            scoreboardPacket = true;
            if (this.linePatched
                    && this.dataObjective != null
                    && this.dataObjective.getName().equals(score.objectiveName())
                    && this.lineOwner.equals(score.owner())) {
                // Vanilla has replaced our display with the new server value.
                // Keep the owner/team state and refresh the restore snapshot.
                this.originalLineDisplay =
                        findEntry(this.dataObjective, this.lineOwner)
                                .map(PlayerScoreEntry::display)
                                .orElse(null);
            }
        } else if (packet instanceof ClientboundResetScorePacket reset) {
            scoreboardPacket = true;
            if (this.linePatched
                    && this.dataObjective != null
                    && this.lineOwner.equals(reset.owner())
                    && (reset.objectiveName() == null
                            || this.dataObjective.getName().equals(reset.objectiveName()))) {
                restoreLineTeam();
                clearLineState();
            }
        } else if (packet instanceof ClientboundSetObjectivePacket objectivePacket) {
            scoreboardPacket = true;
            if (this.dataObjective != null
                    && this.dataObjective.getName().equals(objectivePacket.getObjectiveName())) {
                if (objectivePacket.getMethod() == ClientboundSetObjectivePacket.METHOD_REMOVE) {
                    clearDataState();
                } else {
                    // Vanilla has just installed the authoritative title.
                    this.titlePatched = false;
                    this.originalTitle = null;
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

    private void syncClientData() {
        if (!this.enabled.get()) {
            if (this.dataObjective != null
                    || this.titlePatched
                    || this.linePatched
                    || this.addedLine) restoreData();
            return;
        }
        Objective objective = currentObjective();
        if (objective == null) {
            restoreData();
            return;
        }
        if (this.dataObjective != objective) {
            restoreData();
            this.dataObjective = objective;
        }

        long animationTime = System.currentTimeMillis();
        if (this.replaceTitle.get()) {
            if (!this.titlePatched) {
                this.originalTitle = objective.getDisplayName();
                this.titlePatched = true;
            }
            objective.setDisplayName(DynamicMiniMessage.parse(this.title.get(), animationTime));
        } else {
            restoreTitle();
        }

        if (this.mode.get() == Mode.ADD_LAST_LINE) {
            restoreLine();
            syncAddedLine(objective, animationTime);
            return;
        }
        restoreAddedLine();
        if (this.mode.get() != Mode.LAST_LINE) {
            restoreLine();
            return;
        }
        List<PlayerScoreEntry> entries = visibleEntries(objective);
        if (entries.isEmpty()) {
            restoreLine();
            return;
        }
        PlayerScoreEntry bottom = entries.get(entries.size() - 1);
        if (this.linePatched && !this.lineOwner.equals(bottom.owner())) restoreLine();
        if (!this.linePatched) {
            this.lineOwner = bottom.owner();
            this.originalLineDisplay = bottom.display();
            this.originalLineTeam = objective.getScoreboard().getPlayersTeam(this.lineOwner);
            this.linePatched = true;
        }
        detachLineTeam();
        scoreAccess(objective, this.lineOwner)
                .display(DynamicMiniMessage.parse(this.lastLine.get(), animationTime));
    }

    private void syncAddedLine(Objective objective, long animationTime) {
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
        if (this.addedLineObjective != objective) {
            restoreAddedLine();
            this.addedLineObjective = objective;
        }
        int bottomScore =
                serverEntries.isEmpty() ? 0 : serverEntries.get(serverEntries.size() - 1).value();
        int addedScore = bottomScore == Integer.MIN_VALUE ? Integer.MIN_VALUE : bottomScore - 1;
        ScoreAccess access = scoreAccess(objective, ADDED_LINE_OWNER);
        if (!this.addedLine || access.get() != addedScore) access.set(addedScore);
        access.display(DynamicMiniMessage.parse(this.lastLine.get(), animationTime));
        access.numberFormatOverride(BlankFormat.INSTANCE);
        this.addedLine = true;
    }

    private void restoreAddedLine() {
        if (this.addedLine && this.addedLineObjective != null) {
            this.addedLineObjective
                    .getScoreboard()
                    .resetSinglePlayerScore(
                            ScoreHolder.forNameOnly(ADDED_LINE_OWNER), this.addedLineObjective);
        }
        this.addedLine = false;
        this.addedLineObjective = null;
    }

    private static java.util.Optional<PlayerScoreEntry> findEntry(
            Objective objective, String owner) {
        return objective.getScoreboard().listPlayerScores(objective).stream()
                .filter(entry -> entry.owner().equals(owner))
                .findFirst();
    }

    private void refreshLineTeamAfterPacket(ClientboundSetPlayerTeamPacket packet) {
        if (!this.linePatched || this.dataObjective == null) return;
        if (packet.getTeamAction() == ClientboundSetPlayerTeamPacket.Action.REMOVE
                && this.originalLineTeam != null
                && this.originalLineTeam.getName().equals(packet.getName())) {
            this.originalLineTeam = null;
        }
        if (!packet.getPlayers().contains(this.lineOwner)) return;
        if (packet.getPlayerAction() == ClientboundSetPlayerTeamPacket.Action.REMOVE) {
            this.originalLineTeam = null;
        } else if (packet.getPlayerAction() == ClientboundSetPlayerTeamPacket.Action.ADD) {
            this.originalLineTeam =
                    this.dataObjective.getScoreboard().getPlayerTeam(packet.getName());
        }
    }

    private void detachLineTeam() {
        if (!this.linePatched || this.dataObjective == null) return;
        net.minecraft.world.scores.Scoreboard scoreboard = this.dataObjective.getScoreboard();
        PlayerTeam currentTeam = scoreboard.getPlayersTeam(this.lineOwner);
        if (currentTeam != null) {
            this.originalLineTeam = currentTeam;
            scoreboard.removePlayerFromTeam(this.lineOwner, currentTeam);
        }
    }

    private void restoreLineTeam() {
        if (!this.linePatched || this.dataObjective == null || this.originalLineTeam == null)
            return;
        net.minecraft.world.scores.Scoreboard scoreboard = this.dataObjective.getScoreboard();
        if (scoreboard.getPlayersTeam(this.lineOwner) != null) return;
        PlayerTeam currentTeam = scoreboard.getPlayerTeam(this.originalLineTeam.getName());
        if (currentTeam != null) scoreboard.addPlayerToTeam(this.lineOwner, currentTeam);
    }

    private static ScoreAccess scoreAccess(Objective objective, String owner) {
        return objective
                .getScoreboard()
                .getOrCreatePlayerScore(ScoreHolder.forNameOnly(owner), objective, true);
    }

    private void restoreData() {
        restoreLine();
        restoreAddedLine();
        restoreTitle();
        this.dataObjective = null;
    }

    private void restoreLine() {
        if (this.linePatched && this.dataObjective != null) {
            boolean stillExists =
                    this.dataObjective.getScoreboard().listPlayerScores(this.dataObjective).stream()
                            .anyMatch(entry -> entry.owner().equals(this.lineOwner));
            if (stillExists)
                scoreAccess(this.dataObjective, this.lineOwner).display(this.originalLineDisplay);
            restoreLineTeam();
        }
        clearLineState();
    }

    private void restoreTitle() {
        if (this.titlePatched && this.dataObjective != null)
            this.dataObjective.setDisplayName(this.originalTitle);
        this.titlePatched = false;
        this.originalTitle = null;
    }

    private void clearLineState() {
        this.linePatched = false;
        this.lineOwner = null;
        this.originalLineDisplay = null;
        this.originalLineTeam = null;
    }

    private void clearDataState() {
        restoreLineTeam();
        clearLineState();
        // The objective was already removed by vanilla, so only forget the
        // local synthetic row; there is no score left to reset.
        this.addedLine = false;
        this.addedLineObjective = null;
        this.titlePatched = false;
        this.originalTitle = null;
        this.dataObjective = null;
    }

    private List<Line> customLines(long animationTime) {
        List<Line> lines = new ArrayList<>();
        for (String value : splitLines(this.customLines.get())) {
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

    @Override
    public boolean isEnabled() {
        return this.enabled.get();
    }

    @Override
    public int setEnabled(Minecraft ignoredClient, boolean value) {
        this.enabled.set(value);
        syncClientData();
        return 1;
    }

    public String modeText() {
        return this.mode.serialized();
    }

    public int setMode(Minecraft ignoredClient, String value) {
        this.mode.deserialize(value);
        syncClientData();
        return 1;
    }

    public boolean customMode() {
        return this.mode.get() == Mode.CUSTOM;
    }

    public int setLastLine(Minecraft ignoredClient, String value) {
        this.lastLine.set(sanitize(value, 256));
        syncClientData();
        return 1;
    }

    public int setCustomLines(Minecraft ignoredClient, String value) {
        this.customLines.set(sanitize(value, 2048));
        return 1;
    }

    public int setReplaceTitle(Minecraft ignoredClient, boolean value) {
        this.replaceTitle.set(value);
        syncClientData();
        return 1;
    }

    public int setTitle(Minecraft ignoredClient, String value) {
        this.title.set(sanitize(value, 256));
        syncClientData();
        return 1;
    }

    public int setShowScores(Minecraft ignoredClient, boolean value) {
        this.showScores.set(value);
        return 1;
    }

    private static String sanitize(String value, int maximumLength) {
        String normalized = value == null ? "" : value.replaceAll("[\\p{Cntrl}&&[^\\r\\n]]", "");
        return normalized.length() <= maximumLength
                ? normalized
                : normalized.substring(0, maximumLength);
    }

    private record Line(Component name, Component score) {}

    @Override
    protected Setting[] defineSettings() {
        return new Setting[] {
            mode.describeStored("mode", "Mode", this::setMode),
            lastLine.describeText("last_line", "Last line", this::setLastLine)
                    .visibleWhen(() -> !this.customMode()),
            customLines
                    .describeText("custom_lines", "Custom lines", this::setCustomLines)
                    .visibleWhen(this::customMode),
            replaceTitle.describeStored("replace_title", "Replace title", this::setReplaceTitle),
            title.describeText("title", "Custom title", this::setTitle)
                    .withDefault(com.blanoir.tempos.client.config.ClientBranding.DEFAULT_NAME)
                    .visibleWhen(replaceTitle::storedValue),
            showScores
                    .describeStored("show_scores", "Show scores", this::setShowScores)
                    .visibleWhen(() -> !this.customMode())
        };
    }
}
