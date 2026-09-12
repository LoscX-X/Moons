package com.blanoir.moons.client.utils.plugin;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Finds client-visible custom appearances from effective resource-pack blockstate mappings.
 * Reusing vanilla models or replacing textures alone is not evidence of a plugin block.
 * Packs cannot prove server-side identity; each result includes its observed model references.
 */
public final class PluginModelIndex {
    private static final int MAX_JSON_BYTES = 4 * 1024 * 1024;
    private final ResourceManager resources;
    private final PackResources vanilla;
    private final ArrayDeque<Map.Entry<Identifier, Resource>> pending;
    private final Map<BlockState, Appearance> found = new HashMap<>();
    private int failedFiles;
    private Iterator<BlockState> pendingStates = List.<BlockState>of().iterator();
    private List<ModelRule> rules = List.of();
    private String currentPack = "";

    public PluginModelIndex(ResourceManager resources, PackResources vanilla) {
        this.resources = resources;
        this.vanilla = vanilla;
        this.pending =
                new ArrayDeque<>(
                        resources
                                .listResources("blockstates", id -> id.getPath().endsWith(".json"))
                                .entrySet()
                                .stream()
                                .filter(
                                        entry ->
                                                !entry.getValue()
                                                        .sourcePackId()
                                                        .equals(vanilla.packId()))
                                .sorted(Map.Entry.comparingByKey())
                                .toList());
    }

    /** Bounded file processing on the client thread; no worker holds packs across reloads. */
    public boolean advance(int fileBudget) {
        int files = 0;
        int states = 0;
        long deadline = System.nanoTime() + 4_000_000L;
        while (states < 256 && System.nanoTime() < deadline) {
            if (!pendingStates.hasNext()) {
                if (pending.isEmpty() || files >= fileBudget) break;
                var entry = pending.removeFirst();
                files++;
                try {
                    prepare(entry.getKey(), entry.getValue());
                } catch (IOException | RuntimeException exception) {
                    failedFiles++;
                }
                continue;
            }
            BlockState state = pendingStates.next();
            states++;
            Set<Identifier> models = new HashSet<>();
            for (ModelRule rule : rules) {
                if (rule.condition().test(state)) models.addAll(rule.models());
            }
            if (!models.isEmpty()) {
                found.put(state, new Appearance(models.stream().sorted().toList(), currentPack));
            }
        }
        return pending.isEmpty() && !pendingStates.hasNext();
    }

    public Map<BlockState, Appearance> snapshot() {
        return Map.copyOf(found);
    }

    public int failedFiles() {
        return failedFiles;
    }

    private void prepare(Identifier location, Resource resource) throws IOException {
        String path = location.getPath();
        Identifier blockId =
                Identifier.fromNamespaceAndPath(
                        location.getNamespace(),
                        path.substring("blockstates/".length(), path.length() - ".json".length()));
        Block block = BuiltInRegistries.BLOCK.getOptional(blockId).orElse(null);
        if (block == null || block.defaultBlockState().isAir()) return;
        JsonObject definition;
        try (InputStream stream = resource.open()) {
            definition = readJson(stream);
        }
        Set<Identifier> vanillaModels = new HashSet<>();
        var original = vanilla.getResource(PackType.CLIENT_RESOURCES, location);
        if (original != null) {
            try (InputStream stream = original.get()) {
                collectModels(readJson(stream), vanillaModels);
            }
        }
        List<ModelRule> customRules = new ArrayList<>();
        for (ModelRule rule : compileRules(definition, block)) {
            Set<Identifier> models = new HashSet<>(rule.models());
            models.removeAll(vanillaModels);
            models.removeIf(
                    model ->
                            resources
                                    .getResource(
                                            Identifier.fromNamespaceAndPath(
                                                    model.getNamespace(),
                                                    "models/" + model.getPath() + ".json"))
                                    .isEmpty());
            if (!models.isEmpty()) {
                customRules.add(new ModelRule(rule.condition(), Set.copyOf(models)));
            }
        }
        rules = List.copyOf(customRules);
        currentPack = resource.sourcePackId();
        pendingStates =
                rules.isEmpty()
                        ? List.<BlockState>of().iterator()
                        : block.getStateDefinition().getPossibleStates().iterator();
    }

    /** Resolves variants, weighted model arrays and multipart conditions for one actual state. */
    public static Set<Identifier> modelsForState(JsonObject definition, BlockState state) {
        Set<Identifier> models = new HashSet<>();
        for (ModelRule rule : compileRules(definition, state.getBlock())) {
            if (rule.condition().test(state)) models.addAll(rule.models());
        }
        return models;
    }

    private static List<ModelRule> compileRules(JsonObject definition, Block block) {
        List<ModelRule> rules = new ArrayList<>();
        if (definition.has("variants")) {
            for (var variant : definition.getAsJsonObject("variants").entrySet()) {
                List<Predicate<BlockState>> conditions = new ArrayList<>();
                if (!variant.getKey().isEmpty()) {
                    for (String part : variant.getKey().split(",", -1)) {
                        String[] pair = part.split("=", -1);
                        if (pair.length != 2)
                            throw new IllegalArgumentException("Invalid model variant");
                        conditions.add(propertyCondition(block, pair[0], pair[1]));
                    }
                }
                Set<Identifier> models = new HashSet<>();
                collectModels(variant.getValue(), models);
                rules.add(
                        new ModelRule(
                                state ->
                                        conditions.stream()
                                                .allMatch(condition -> condition.test(state)),
                                models));
            }
        }
        if (definition.has("multipart")) {
            for (var element : definition.getAsJsonArray("multipart")) {
                JsonObject part = element.getAsJsonObject();
                Predicate<BlockState> condition =
                        part.has("when")
                                ? compileCondition(part.getAsJsonObject("when"), block)
                                : state -> true;
                Set<Identifier> models = new HashSet<>();
                collectModels(part.get("apply"), models);
                rules.add(new ModelRule(condition, models));
            }
        }
        return rules;
    }

    private static Predicate<BlockState> compileCondition(JsonObject condition, Block block) {
        List<Predicate<BlockState>> conditions = new ArrayList<>();
        for (var entry : condition.entrySet()) {
            if (entry.getKey().equals("OR") || entry.getKey().equals("AND")) {
                boolean and = entry.getKey().equals("AND");
                List<Predicate<BlockState>> children = new ArrayList<>();
                for (var child : entry.getValue().getAsJsonArray()) {
                    children.add(compileCondition(child.getAsJsonObject(), block));
                }
                conditions.add(
                        state ->
                                and
                                        ? children.stream().allMatch(test -> test.test(state))
                                        : children.stream().anyMatch(test -> test.test(state)));
            } else
                conditions.add(
                        propertyCondition(block, entry.getKey(), entry.getValue().getAsString()));
        }
        return state -> conditions.stream().allMatch(test -> test.test(state));
    }

    private static Predicate<BlockState> propertyCondition(
            Block block, String name, String expected) {
        Property<?> property = block.getStateDefinition().getProperty(name);
        if (property == null)
            throw new IllegalArgumentException("Unknown model state property: " + name);
        boolean negate = expected.startsWith("!");
        String choices = negate ? expected.substring(1) : expected;
        Set<String> values = new HashSet<>(List.of(choices.split("\\|", -1)));
        if (values.stream().anyMatch(candidate -> property.getValue(candidate).isEmpty())) {
            throw new IllegalArgumentException("Unknown model state value: " + expected);
        }
        return state -> negate != values.contains(value(state, property));
    }

    private static <T extends Comparable<T>> String value(BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }

    private static void collectModels(JsonElement element, Set<Identifier> models) {
        if (element == null || element.isJsonNull()) return;
        if (element.isJsonArray()) {
            for (var child : element.getAsJsonArray()) collectModels(child, models);
        } else if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            if (object.has("model") && object.get("model").isJsonPrimitive()) {
                Identifier id = Identifier.tryParse(object.get("model").getAsString());
                if (id != null) models.add(id);
            }
            for (var entry : object.entrySet()) {
                if (!entry.getKey().equals("model")) collectModels(entry.getValue(), models);
            }
        }
    }

    private static JsonObject readJson(InputStream stream) throws IOException {
        byte[] bytes = stream.readNBytes(MAX_JSON_BYTES + 1);
        if (bytes.length > MAX_JSON_BYTES)
            throw new IOException("Blockstate definition is too large");
        return JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    public record Appearance(List<Identifier> models, String pack) {}

    private record ModelRule(Predicate<BlockState> condition, Set<Identifier> models) {}
}
