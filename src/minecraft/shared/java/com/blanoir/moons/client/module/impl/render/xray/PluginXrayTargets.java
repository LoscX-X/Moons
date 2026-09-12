package com.blanoir.moons.client.module.impl.render.xray;

import com.blanoir.moons.client.access.MinecraftClientAccess;
import com.blanoir.moons.client.chat.ClientChat;
import com.blanoir.moons.client.config.Settings;
import com.blanoir.moons.client.utils.plugin.PluginBlockSelector;
import com.blanoir.moons.client.utils.plugin.PluginClientContext;
import com.blanoir.moons.client.utils.plugin.PluginModelIndex;
import com.blanoir.moons.client.utils.plugin.PluginServerStore;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.PackResources;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Server-scoped appearance targets with a lock-free, immutable lookup for the scanner. */
public final class PluginXrayTargets {
    private static final String ENABLED_KEY = "xray.plugin.compatibility";
    private static final String TARGETS_KEY = "xray.plugin.targets";
    private static volatile boolean enabled = Settings.getBoolean(ENABLED_KEY, false);
    private static final String SERVER_SECTION = "plugin_xray";
    private static final Map<String, LinkedHashMap<String, SavedTarget>> SAVED =
            new LinkedHashMap<>();
    private static boolean legacyMigrated;
    private static volatile Map<BlockState, StateTarget> compiled = Map.of();
    private static String currentScope = "";
    private static Object modelSet;
    private static List<PackResources> packs = List.of();
    private static PluginModelIndex indexing;
    private static Map<BlockState, PluginModelIndex.Appearance> automatic = Map.of();
    private static PluginBlockCatalog catalog = new PluginBlockCatalog();
    private static volatile Map<BlockState, String> appearanceIds = Map.of();
    private static long revision;

    private PluginXrayTargets() {}

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setEnabled(Minecraft client, boolean value) {
        enabled = value;
        Settings.setBoolean(ENABLED_KEY, value);
        updateContext(client);
        resetIndex();
        rebuild();
        refresh(client);
    }

    /** Called on the client thread before scanning, including the initial attached world. */
    public static void updateContext(Minecraft client) {
        String scope = PluginClientContext.scope(client);
        if (currentScope.equals(scope)) return;
        currentScope = scope;
        catalog = new PluginBlockCatalog();
        loadCurrent(client);
        resetIndex();
        rebuild();
        refresh(client);
    }

    public static XrayTarget find(BlockState state) {
        return compiled.get(state);
    }

    public static boolean isRecognized(BlockState state) {
        return enabled && appearanceIds.containsKey(state);
    }

    public static void tick(Minecraft client) {
        updateContext(client);
        if (!enabled
                || client == null
                || client.level == null
                || MinecraftClientAccess.hasOverlay(client)) return;
        Object currentModels = client.getModelManager().getBlockStateModelSet();
        List<PackResources> currentPacks = client.getResourceManager().listPacks().toList();
        if (modelSet != currentModels || !packs.equals(currentPacks)) {
            modelSet = currentModels;
            packs = currentPacks;
            automatic = Map.of();
            appearanceIds = Map.of();
            rebuild();
            refresh(client);
            try {
                indexing =
                        new PluginModelIndex(
                                client.getResourceManager(),
                                MinecraftClientAccess.vanillaResources(client));
            } catch (RuntimeException exception) {
                indexing = null;
                ClientChat.send(client, "Plugin Xray: could not index resource-pack models.");
            }
        }
        if (indexing != null && indexing.advance(2)) {
            automatic = indexing.snapshot();
            Map<BlockState, String> bindings = new HashMap<>();
            automatic.forEach(
                    (state, appearance) ->
                            bindings.put(
                                    state,
                                    catalog.discover(
                                                    appearance.models().stream()
                                                            .map(Object::toString)
                                                            .toList())
                                            .id()));
            appearanceIds = Map.copyOf(bindings);
            int failures = indexing.failedFiles();
            indexing = null;
            saveAppearances(client);
            rebuild();
            refresh(client);
            ClientChat.send(
                    client,
                    "Plugin Xray: "
                            + automatic.size()
                            + " custom appearance states indexed."
                            + (failures == 0
                                    ? ""
                                    : " Skipped " + failures + " unsupported definitions."));
        }
    }

    public static int automaticCount() {
        return automatic.size();
    }

    public static long revision() {
        return revision;
    }

    public static String scope() {
        return currentScope;
    }

    public static boolean isIndexing() {
        return indexing != null;
    }

    public record BlockEntry(
            String id,
            String name,
            String detail,
            boolean enabled,
            int rgb,
            BlockState preview,
            int stateCount) {}

    /** Immutable UI snapshot; persisted entries remain visible while their models are unavailable. */
    public static List<BlockEntry> blocks() {
        Map<String, List<BlockState>> states = new HashMap<>();
        appearanceIds.forEach(
                (state, id) -> states.computeIfAbsent(id, ignored -> new ArrayList<>()).add(state));
        List<BlockEntry> result = new ArrayList<>();
        for (var entry : catalog.entries()) {
            List<BlockState> matching =
                    states.getOrDefault(entry.id(), List.of()).stream()
                            .sorted(
                                    Comparator.comparing(
                                            state -> PluginBlockSelector.capture(state).key()))
                            .toList();
            result.add(
                    new BlockEntry(
                            entry.id(),
                            entry.name(),
                            String.join(", ", entry.models()),
                            entry.enabled(),
                            entry.rgb(),
                            matching.isEmpty() ? null : matching.getFirst(),
                            matching.size()));
        }
        for (var target : SAVED.getOrDefault(currentScope, new LinkedHashMap<>()).values()) {
            BlockState preview = null;
            try {
                var selector = PluginBlockSelector.parse(target.selector());
                preview =
                        selector.block().getStateDefinition().getPossibleStates().stream()
                                .filter(selector::matches)
                                .findFirst()
                                .orElse(null);
            } catch (IllegalArgumentException ignored) {
                /* Keep older-version rules visible. */
            }
            result.add(
                    new BlockEntry(
                            "manual:" + target.selector(),
                            target.selector(),
                            "Manual state rule",
                            target.enabled(),
                            target.rgb(),
                            preview,
                            preview == null ? 0 : 1));
        }
        return List.copyOf(result);
    }

    /** Scope is captured by the UI so a queued edit can never reach another server. */
    public static void editBlock(
            Minecraft client, String scope, String id, boolean selected, int rgb) {
        requireScope(client);
        if (!currentScope.equals(scope))
            throw new IllegalArgumentException("The server changed; reopen Plugin Blocks.");
        if (id.startsWith("manual:")) {
            String selector = id.substring("manual:".length());
            var next = new LinkedHashMap<>(SAVED.getOrDefault(currentScope, new LinkedHashMap<>()));
            if (!next.containsKey(selector))
                throw new IllegalArgumentException("Unknown manual target");
            next.put(selector, new SavedTarget(selector, rgb & 0xffffff, selected));
            save(next);
            SAVED.put(currentScope, next);
        } else {
            var next = PluginBlockCatalog.read(catalog.toJson());
            next.edit(id, selected, rgb);
            PluginServerStore.update(
                    currentScope, SERVER_SECTION, section -> section.add("blocks", next.toJson()));
            catalog = next;
        }
        rebuild();
        refresh(client);
    }

    public static String describe(BlockState state) {
        var appearance = automatic.get(state);
        return appearance == null
                ? "No custom model mapping detected."
                : "Models: " + appearance.models() + " (pack: " + appearance.pack() + ").";
    }

    public static String colorString() {
        return Settings.getString("xray.plugin.color", "#ef8002");
    }

    public static void setColor(Minecraft client, int[] rgb) {
        Settings.setString(
                "xray.plugin.color", String.format("#%02x%02x%02x", rgb[0], rgb[1], rgb[2]));
        rebuild();
        refresh(client);
    }

    public static void resetIndex() {
        modelSet = null;
        packs = List.of();
        indexing = null;
        automatic = Map.of();
        appearanceIds = Map.of();
        revision++;
    }

    public static String add(Minecraft client, String input, CustomXrayTargets.ColorValue color) {
        requireScope(client);
        PluginBlockSelector selector =
                input.equalsIgnoreCase("looking")
                        ? PluginClientContext.lookingAt(client)
                        : PluginBlockSelector.parse(input);
        int rgb = (color.red() << 16) | (color.green() << 8) | color.blue();
        var targets = new LinkedHashMap<>(SAVED.getOrDefault(currentScope, new LinkedHashMap<>()));
        targets.put(selector.key(), new SavedTarget(selector.key(), rgb, true));
        save(targets);
        SAVED.put(currentScope, targets);
        rebuild();
        refresh(client);
        return selector.key();
    }

    public static boolean remove(Minecraft client, String input) {
        requireScope(client);
        String key =
                input.equalsIgnoreCase("looking")
                        ? PluginClientContext.lookingAt(client).key()
                        : PluginBlockSelector.parse(input).key();
        var targets = new LinkedHashMap<>(SAVED.getOrDefault(currentScope, new LinkedHashMap<>()));
        if (targets.remove(key) == null) return false;
        save(targets);
        SAVED.put(currentScope, targets);
        rebuild();
        refresh(client);
        return true;
    }

    public static List<String> list(Minecraft client) {
        requireScope(client);
        Map<String, SavedTarget> targets = SAVED.get(currentScope);
        if (targets == null) return List.of();
        return targets.values().stream()
                .map(
                        target -> {
                            String status = "";
                            try {
                                PluginBlockSelector.parse(target.selector());
                            } catch (IllegalArgumentException exception) {
                                status = " (unavailable in this version)";
                            }
                            return target.selector()
                                    + String.format(" #%06x", target.rgb())
                                    + (target.enabled() ? " enabled" : " disabled")
                                    + status;
                        })
                .toList();
    }

    public static void clear(Minecraft client) {
        requireScope(client);
        save(Map.of());
        SAVED.remove(currentScope);
        rebuild();
        refresh(client);
    }

    private static void requireScope(Minecraft client) {
        updateContext(client);
        if (currentScope.isEmpty())
            throw new IllegalArgumentException("Join a server or singleplayer world first.");
    }

    private static void refresh(Minecraft client) {
        OreCache.removeInvalidPositions(client);
        OreScanner.requestFullRescan(client);
    }

    private static void rebuild() {
        compiled.values().forEach(target -> target.active = false);
        Map<BlockState, StateTarget> next = new HashMap<>();
        Map<String, SavedTarget> saved = SAVED.get(currentScope);
        if (enabled && saved != null) {
            List<StateTarget> targets = new ArrayList<>();
            for (SavedTarget target : saved.values()) {
                if (!target.enabled()) continue;
                try {
                    targets.add(
                            new StateTarget(
                                    PluginBlockSelector.parse(target.selector()), target.rgb()));
                } catch (IllegalArgumentException ignored) {
                    /* Preserve unavailable selectors on disk. */
                }
            }
            // Stable sort: exact captures win over broader rules; insertion order breaks ties.
            targets.sort(
                    Comparator.comparingInt((StateTarget target) -> target.selector.specificity())
                            .reversed());
            for (StateTarget target : targets) {
                for (BlockState state :
                        target.selector.block().getStateDefinition().getPossibleStates()) {
                    if (target.matches(state)) next.putIfAbsent(state, target);
                }
            }
        }
        if (enabled) {
            appearanceIds.forEach(
                    (state, id) -> {
                        var entry = catalog.get(id);
                        if (entry != null && entry.enabled())
                            next.putIfAbsent(
                                    state,
                                    new StateTarget(
                                            PluginBlockSelector.capture(state), entry.rgb()));
                    });
        }
        compiled = Map.copyOf(next);
        revision++;
    }

    private static void loadCurrent(Minecraft client) {
        if (currentScope.isEmpty()) return;
        SAVED.remove(currentScope);
        try {
            migrateLegacy();
        } catch (RuntimeException exception) {
            ClientChat.send(
                    client,
                    "Plugin Xray: legacy configuration migration is incomplete: "
                            + exception.getMessage());
        }
        try {
            JsonObject root = PluginServerStore.read(currentScope);
            JsonObject section =
                    root.has(SERVER_SECTION)
                            ? root.getAsJsonObject(SERVER_SECTION)
                            : new JsonObject();
            if (section.has("blocks"))
                catalog = PluginBlockCatalog.read(section.getAsJsonArray("blocks"));
            // Older appearance snapshots supply names only, never selections or live bindings.
            if (section.has("appearances")) {
                for (var value : section.getAsJsonArray("appearances")) {
                    catalog.discover(
                            value.getAsJsonObject().getAsJsonArray("models").asList().stream()
                                    .map(element -> element.getAsString())
                                    .toList());
                }
            }
            var targets = new LinkedHashMap<String, SavedTarget>();
            if (section.has("manual_targets")) {
                for (var element : section.getAsJsonArray("manual_targets")) {
                    var entry = element.getAsJsonObject();
                    String selector = entry.get("state").getAsString();
                    int rgb = entry.get("rgb").getAsInt() & 0xffffff;
                    targets.put(
                            selector,
                            new SavedTarget(
                                    selector,
                                    rgb,
                                    !entry.has("enabled") || entry.get("enabled").getAsBoolean()));
                }
            }
            SAVED.put(currentScope, targets);
        } catch (RuntimeException exception) {
            ClientChat.send(
                    client,
                    "Plugin Xray: could not read server configuration: " + exception.getMessage());
        }
    }

    private static void migrateLegacy() {
        if (legacyMigrated) return;
        JsonObject root =
                JsonParser.parseString(Settings.getString(TARGETS_KEY, "{}")).getAsJsonObject();
        for (var scope : root.entrySet()) {
            JsonArray entries = scope.getValue().getAsJsonArray();
            // Copy selectors unchanged: another supported Minecraft version may resolve them.
            PluginServerStore.update(
                    scope.getKey(),
                    SERVER_SECTION,
                    section -> {
                        if (!section.has("manual_targets"))
                            section.add("manual_targets", entries.deepCopy());
                    });
        }
        Settings.remove(TARGETS_KEY);
        legacyMigrated = true;
    }

    private static void save(Map<String, SavedTarget> targets) {
        JsonArray entries = new JsonArray();
        targets.values()
                .forEach(
                        target -> {
                            JsonObject entry = new JsonObject();
                            entry.addProperty("state", target.selector());
                            entry.addProperty("rgb", target.rgb());
                            entry.addProperty("enabled", target.enabled());
                            entries.add(entry);
                        });
        PluginServerStore.update(
                currentScope, SERVER_SECTION, section -> section.add("manual_targets", entries));
    }

    private static void saveAppearances(Minecraft client) {
        if (currentScope.isEmpty()) return;
        JsonArray entries = new JsonArray();
        automatic.entrySet().stream()
                .sorted(
                        Comparator.comparing(
                                entry -> PluginBlockSelector.capture(entry.getKey()).key()))
                .forEach(
                        entry -> {
                            JsonObject value = new JsonObject();
                            value.addProperty(
                                    "state", PluginBlockSelector.capture(entry.getKey()).key());
                            JsonArray models = new JsonArray();
                            entry.getValue()
                                    .models()
                                    .forEach(model -> models.add(model.toString()));
                            value.add("models", models);
                            value.addProperty("pack", entry.getValue().pack());
                            entries.add(value);
                        });
        try {
            PluginServerStore.update(
                    currentScope,
                    SERVER_SECTION,
                    section -> {
                        section.add("appearances", entries);
                        section.add("blocks", catalog.toJson());
                    });
        } catch (RuntimeException exception) {
            ClientChat.send(
                    client,
                    "Plugin Xray: could not save server configuration: " + exception.getMessage());
        }
    }

    private record SavedTarget(String selector, int rgb, boolean enabled) {}

    private static final class StateTarget implements XrayTarget {
        private final PluginBlockSelector selector;
        private final int rgb;
        private volatile boolean active = true;

        private StateTarget(PluginBlockSelector selector, int rgb) {
            this.selector = selector;
            this.rgb = rgb;
        }

        @Override
        public String commandName() {
            return selector.key();
        }

        @Override
        public boolean isEnabled() {
            return enabled && active;
        }

        @Override
        public int red() {
            return rgb >> 16 & 255;
        }

        @Override
        public int green() {
            return rgb >> 8 & 255;
        }

        @Override
        public int blue() {
            return rgb & 255;
        }

        @Override
        public boolean matches(Block block) {
            return selector.block() == block;
        }

        @Override
        public boolean matches(BlockState state) {
            return selector.matches(state);
        }

        @Override
        public boolean requiresCurrentState() {
            return true;
        }
    }
}
