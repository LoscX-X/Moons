package com.blanoir.moons.client.utils.plugin;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.block.Block;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.resources.IResource;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.client.resources.IResourcePack;
import net.minecraft.util.ResourceLocation;

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
    private final IResourceManager resources;
    private final IResourcePack vanilla;
    private final ArrayDeque<ResourceLocation> pending;
    private final Map<IBlockState, Appearance> found = new HashMap<>();
    private int failedFiles;
    private Iterator<IBlockState> pendingStates = List.<IBlockState>of().iterator();
    private List<ModelRule> rules = List.of();
    private String currentPack = "";
    private boolean currentServerPack;

    public PluginModelIndex(IResourceManager resources, IResourcePack vanilla) {
        this.resources = resources;
        this.vanilla = vanilla;
        this.pending = new ArrayDeque<>();
        Block.blockRegistry.getKeys().stream()
                .sorted(java.util.Comparator.comparing(Object::toString))
                .forEach(
                        id ->
                                pending.add(
                                        new ResourceLocation(
                                                id.getResourceDomain(),
                                                "blockstates/" + id.getResourcePath() + ".json")));
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
                    prepare(entry);
                } catch (java.io.FileNotFoundException missing) {
                    // Several 1.8 blocks have special renderers and no blockstate JSON.
                } catch (IOException | RuntimeException exception) {
                    failedFiles++;
                }
                continue;
            }
            IBlockState state = pendingStates.next();
            states++;
            Set<ResourceLocation> models = new HashSet<>();
            for (ModelRule rule : rules) {
                if (rule.condition().test(state)) models.addAll(rule.models());
            }
            if (!models.isEmpty()) {
                found.put(
                        state,
                        new Appearance(
                                models.stream()
                                        .sorted(java.util.Comparator.comparing(Object::toString))
                                        .toList(),
                                currentPack,
                                currentServerPack));
            }
        }
        return pending.isEmpty() && !pendingStates.hasNext();
    }

    public Map<IBlockState, Appearance> snapshot() {
        return Map.copyOf(found);
    }

    public int failedFiles() {
        return failedFiles;
    }

    private void prepare(ResourceLocation location) throws IOException {
        String path = location.getResourcePath();
        ResourceLocation blockId =
                new ResourceLocation(
                        location.getResourceDomain(),
                        path.substring("blockstates/".length(), path.length() - ".json".length()));
        Block block =
                Block.blockRegistry.containsKey(blockId)
                        ? Block.blockRegistry.getObject(blockId)
                        : null;
        if (block == null || block == net.minecraft.init.Blocks.air) return;
        // 1.8 merges variant maps across packs in priority order; reading only the top
        // resource would lose valid variants contributed by lower-priority packs.
        JsonObject definition = new JsonObject();
        JsonObject variants = new JsonObject();
        definition.add("variants", variants);
        String pack = vanilla.getPackName();
        for (IResource resource : resources.getAllResources(location)) {
            try (InputStream stream = resource.getInputStream()) {
                JsonObject layer = readJson(stream);
                if (layer.has("variants"))
                    for (var entry : layer.getAsJsonObject("variants").entrySet())
                        variants.add(entry.getKey(), entry.getValue());
                if (layer.has("multipart")) definition.add("multipart", layer.get("multipart"));
                pack = resource.getResourcePackName();
            }
        }
        if (pack.equals(vanilla.getPackName())) return;
        Set<ResourceLocation> vanillaModels = new HashSet<>();
        if (vanilla.resourceExists(location)) {
            try (InputStream stream = vanilla.getInputStream(location)) {
                collectModels(readJson(stream), vanillaModels);
            }
        }
        List<ModelRule> customRules = new ArrayList<>();
        for (ModelRule rule : compileRules(definition, block)) {
            Set<ResourceLocation> models = new HashSet<>(rule.models());
            models.removeAll(vanillaModels);
            models.removeIf(
                    model ->
                            !resourceExists(
                                    new ResourceLocation(
                                            model.getResourceDomain(),
                                            "models/" + model.getResourcePath() + ".json")));
            if (!models.isEmpty()) {
                customRules.add(new ModelRule(rule.condition(), Set.copyOf(models)));
            }
        }
        rules = List.copyOf(customRules);
        currentPack = pack;
        var server =
                net.minecraft.client.Minecraft.getMinecraft()
                        .getResourcePackRepository()
                        .getResourcePackInstance();
        currentServerPack = server != null && currentPack.equals(server.getPackName());
        pendingStates =
                rules.isEmpty()
                        ? List.<IBlockState>of().iterator()
                        : block.getBlockState().getValidStates().iterator();
    }

    /** Resolves variants, weighted model arrays and multipart conditions for one actual state. */
    public static Set<ResourceLocation> modelsForState(JsonObject definition, IBlockState state) {
        Set<ResourceLocation> models = new HashSet<>();
        for (ModelRule rule : compileRules(definition, state.getBlock())) {
            if (rule.condition().test(state)) models.addAll(rule.models());
        }
        return models;
    }

    private static List<ModelRule> compileRules(JsonObject definition, Block block) {
        List<ModelRule> rules = new ArrayList<>();
        if (definition.has("variants")) {
            for (var variant : definition.getAsJsonObject("variants").entrySet()) {
                if (variant.getKey().equals("inventory")) continue;
                List<Predicate<IBlockState>> conditions = new ArrayList<>();
                if (!variant.getKey().isEmpty()
                        && !variant.getKey().equals("normal")
                        && !variant.getKey().equals("inventory")) {
                    for (String part : variant.getKey().split(",", -1)) {
                        String[] pair = part.split("=", -1);
                        if (pair.length != 2)
                            throw new IllegalArgumentException("Invalid model variant");
                        conditions.add(propertyCondition(block, pair[0], pair[1]));
                    }
                }
                Set<ResourceLocation> models = new HashSet<>();
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
                Predicate<IBlockState> condition =
                        part.has("when")
                                ? compileCondition(part.getAsJsonObject("when"), block)
                                : state -> true;
                Set<ResourceLocation> models = new HashSet<>();
                collectModels(part.get("apply"), models);
                rules.add(new ModelRule(condition, models));
            }
        }
        return rules;
    }

    private static Predicate<IBlockState> compileCondition(JsonObject condition, Block block) {
        List<Predicate<IBlockState>> conditions = new ArrayList<>();
        for (var entry : condition.entrySet()) {
            if (entry.getKey().equals("OR") || entry.getKey().equals("AND")) {
                boolean and = entry.getKey().equals("AND");
                List<Predicate<IBlockState>> children = new ArrayList<>();
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

    private static Predicate<IBlockState> propertyCondition(
            Block block, String name, String expected) {
        IProperty<?> property =
                block.getBlockState().getProperties().stream()
                        .filter(candidate -> candidate.getName().equals(name))
                        .findFirst()
                        .orElse(null);
        if (property == null)
            throw new IllegalArgumentException("Unknown model state property: " + name);
        boolean negate = expected.startsWith("!");
        String choices = negate ? expected.substring(1) : expected;
        Set<String> values = new HashSet<>(List.of(choices.split("\\|", -1)));
        if (values.stream()
                .anyMatch(
                        candidate ->
                                property.getAllowedValues().stream()
                                        .noneMatch(
                                                value ->
                                                        propertyValue(property, value)
                                                                .equals(candidate)))) {
            throw new IllegalArgumentException("Unknown model state value: " + expected);
        }
        return state -> negate != values.contains(value(state, property));
    }

    private static <T extends Comparable<T>> String value(
            IBlockState state, IProperty<T> property) {
        return property.getName(state.getValue(property));
    }

    private static void collectModels(JsonElement element, Set<ResourceLocation> models) {
        if (element == null || element.isJsonNull()) return;
        if (element.isJsonArray()) {
            for (var child : element.getAsJsonArray()) collectModels(child, models);
        } else if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            if (object.has("model") && object.get("model").isJsonPrimitive()) {
                ResourceLocation id = parseLocation(object.get("model").getAsString());
                if (id != null)
                    id =
                            new ResourceLocation(
                                    id.getResourceDomain(), "block/" + id.getResourcePath());
                if (id != null) models.add(id);
            }
            for (var entry : object.entrySet()) {
                if (!entry.getKey().equals("model")) collectModels(entry.getValue(), models);
            }
        }
    }

    private boolean resourceExists(ResourceLocation id) {
        try (InputStream stream = resources.getResource(id).getInputStream()) {
            return stream != null;
        } catch (IOException failure) {
            return false;
        }
    }

    private static ResourceLocation parseLocation(String value) {
        try {
            return new ResourceLocation(value);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static String propertyValue(IProperty property, Comparable value) {
        return property.getName(value);
    }

    private static JsonObject readJson(InputStream stream) throws IOException {
        byte[] bytes = stream.readNBytes(MAX_JSON_BYTES + 1);
        if (bytes.length > MAX_JSON_BYTES)
            throw new IOException("Blockstate definition is too large");
        return JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    public record Appearance(List<ResourceLocation> models, String pack, boolean serverProvided) {
        public Appearance(List<ResourceLocation> models, String pack) {
            this(models, pack, false);
        }
    }

    private record ModelRule(Predicate<IBlockState> condition, Set<ResourceLocation> models) {}
}
