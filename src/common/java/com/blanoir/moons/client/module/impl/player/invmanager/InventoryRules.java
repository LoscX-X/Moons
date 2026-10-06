package com.blanoir.moons.client.module.impl.player.invmanager;

import com.blanoir.moons.client.config.Settings;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.mojang.serialization.JsonOps;

import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Versioned user rules. Registry IDs survive missing items; unknown data never becomes a wildcard. */
public final class InventoryRules {
    private static final Gson JSON = new com.google.gson.GsonBuilder().serializeNulls().create();
    private static final String KEY = "invmanager.rules";
    public static final String BLOCK = "blocks";
    public static final List<String> SAMPLE_FIELDS =
            List.of(
                    "potion_contents",
                    "enchantments",
                    "custom_name",
                    "lore",
                    "custom_model_data",
                    "item_model",
                    "custom_data",
                    "attribute_modifiers");
    private static String loaded;
    private static State state = new State(1, List.of());
    private static String error = "";

    public record Entry(
            String item,
            Map<String, JsonElement> components,
            boolean allowSpecial,
            Map<String, JsonElement> sample) {
        public Entry {
            if (item == null || Identifier.tryParse(item) == null)
                throw new IllegalArgumentException("Invalid item ID");
            components = components == null ? Map.of() : Map.copyOf(components);
            sample = sample == null ? components : Map.copyOf(sample);
        }

        public boolean matches(ItemStack stack) {
            if (stack.isEmpty() || !item.equals(itemId(stack)) || !available()) return false;
            for (var component : components.entrySet()) {
                var type = componentType(component.getKey());
                if (type == null) return false;
                JsonElement actual = encode(stack, type);
                if (actual == null || !actual.equals(component.getValue())) return false;
            }
            return true;
        }

        public boolean available() {
            if (!BuiltInRegistries.ITEM.containsKey(Identifier.tryParse(item))) return false;
            for (String key : components.keySet()) if (componentType(key) == null) return false;
            return true;
        }

        public boolean authorizesSpecial(ItemStack stack) {
            // Explicit samples must cover every field inspected by Protect special. A potion
            // predicate alone must not authorize every named server item using that potion ID.
            return allowSpecial
                    && !components.isEmpty()
                    && matches(stack)
                    && components
                            .keySet()
                            .containsAll(SAMPLE_FIELDS.subList(2, SAMPLE_FIELDS.size()))
                    && !stack.has(net.minecraft.core.component.DataComponents.CONTAINER)
                    && !stack.has(net.minecraft.core.component.DataComponents.BUNDLE_CONTENTS);
        }

        public ItemStack icon() {
            Identifier id = Identifier.tryParse(item);
            if (!BuiltInRegistries.ITEM.containsKey(id)) return ItemStack.EMPTY;
            ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.getValue(id));
            Minecraft client = Minecraft.getInstance();
            if (client.level != null) {
                for (var field : components.entrySet())
                    applyComponent(stack, field.getKey(), field.getValue());
            }
            return stack;
        }
    }

    public record Rule(
            String id,
            String name,
            InventoryRole base,
            boolean onlyListed,
            boolean preferFirst,
            List<Entry> include,
            List<Entry> exclude) {
        public Rule {
            if (id == null || name == null || base == null)
                throw new IllegalArgumentException("Incomplete rule");
            include = List.copyOf(include);
            exclude = List.copyOf(exclude);
        }

        public boolean matches(ItemStack stack) {
            if (stack.isEmpty()) return false;
            String stackId = itemId(stack);
            for (Entry entry : exclude)
                if (entry.matches(stack) || entry.item().equals(stackId) && !entry.available())
                    return false;
            for (Entry entry : include)
                if (entry.item().equals(stackId) && !entry.available()) return false;
            for (Entry entry : include) if (entry.matches(stack)) return true;
            return !onlyListed && InventoryItems.matches(base, stack);
        }

        public boolean mayMove(ItemStack stack, boolean protectSpecial) {
            if (stack.isEmpty() || !protectSpecial || InventoryItems.protection(stack).isEmpty())
                return true;
            for (Entry entry : include) if (entry.authorizesSpecial(stack)) return true;
            return false;
        }

        public int rank(ItemStack stack) {
            for (int i = 0; i < include.size(); i++) if (include.get(i).matches(stack)) return i;
            return Integer.MAX_VALUE;
        }

        public int specificity() {
            if (!onlyListed) return 0;
            for (Entry entry : include) if (!entry.components().isEmpty()) return 2;
            return 1;
        }

        public String reason(ItemStack stack) {
            if (stack.isEmpty()) return "Empty";
            if (exclude.stream().anyMatch(entry -> entry.matches(stack))) return "Excluded by rule";
            if (include.stream().anyMatch(entry -> entry.matches(stack)))
                return "Matches allowed item";
            if (!onlyListed && InventoryItems.matches(base, stack))
                return "Matches default category";
            return "Does not match";
        }
    }

    private record State(int version, List<Rule> groups) {}

    private InventoryRules() {}

    private static void load() {
        String raw = Settings.getString(KEY, "");
        if (raw.equals(loaded)) return;
        loaded = raw;
        error = "";
        try {
            State parsed =
                    raw.isBlank() ? new State(1, List.of()) : JSON.fromJson(raw, State.class);
            if (parsed == null || parsed.version != 1 || parsed.groups == null)
                throw new IllegalArgumentException("Unsupported rules version");
            var ids = new java.util.HashSet<String>();
            for (Rule group : parsed.groups)
                if (!ids.add(group.id())) throw new IllegalArgumentException("Duplicate rule ID");
            state = new State(1, List.copyOf(parsed.groups));
        } catch (RuntimeException exception) {
            state = new State(1, List.of());
            error = "Saved item rules are invalid or from a newer version";
        }
    }

    public static String error() {
        load();
        return error;
    }

    public static List<Rule> groups() {
        load();
        return state.groups;
    }

    public static Rule blockRule() {
        load();
        return state.groups.stream()
                .filter(rule -> BLOCK.equals(rule.id()))
                .findFirst()
                .orElse(
                        new Rule(
                                BLOCK,
                                "Blocks",
                                InventoryRole.BLOCK,
                                !error.isEmpty(),
                                false,
                                List.of(),
                                List.of()));
    }

    public static Rule resolve(int slot, InventoryRole role) {
        load();
        if (!error.isEmpty())
            return new Rule(
                    "invalid", "Invalid saved rules", role, true, false, List.of(), List.of());
        if (role == InventoryRole.BLOCK) return blockRule();
        if (role == InventoryRole.CUSTOM) {
            String id = Settings.getString("invmanager.rule.slot." + slot, "");
            return state.groups.stream()
                    .filter(rule -> id.equals(rule.id()))
                    .findFirst()
                    .orElse(
                            new Rule(
                                    id,
                                    "Select an item group",
                                    role,
                                    true,
                                    false,
                                    List.of(),
                                    List.of()));
        }
        return new Rule(role.id(), role.label(), role, false, false, List.of(), List.of());
    }

    public static void assign(int slot, String group) {
        Settings.setString("invmanager.rule.slot." + slot, group);
        InvManagerConfig.setRole(slot, InventoryRole.CUSTOM);
    }

    public static Rule create(String name) {
        Rule rule =
                new Rule(
                        UUID.randomUUID().toString(),
                        name.isBlank() ? "Custom items" : name,
                        InventoryRole.CUSTOM,
                        true,
                        false,
                        List.of(),
                        List.of());
        save(rule);
        return rule;
    }

    public static void save(Rule rule) {
        load();
        if (!error.isEmpty()) throw new IllegalStateException(error);
        var groups = new ArrayList<>(state.groups);
        groups.removeIf(existing -> existing.id().equals(rule.id()));
        groups.add(rule);
        Settings.setString(KEY, JSON.toJson(new State(1, groups)));
    }

    public static void resetBlocks() {
        save(new Rule(BLOCK, "Blocks", InventoryRole.BLOCK, false, false, List.of(), List.of()));
    }

    public static void delete(String id) {
        load();
        if (!error.isEmpty()) return;
        Settings.setString(
                KEY,
                JSON.toJson(
                        new State(
                                1,
                                state.groups.stream()
                                        .filter(rule -> !rule.id().equals(id))
                                        .toList())));
    }

    public static Entry entry(ItemStack stack, boolean sample) {
        var fields = new LinkedHashMap<String, JsonElement>();
        if (sample) {
            for (String field : SAMPLE_FIELDS) {
                var type = componentType(field);
                JsonElement value = type == null ? null : encode(stack, type);
                if (value == null)
                    throw new IllegalArgumentException("Cannot capture component: " + field);
                fields.put(field, value);
            }
        }
        return new Entry(itemId(stack), fields, false, fields);
    }

    public static String itemId(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    public static List<ItemStack> search(String query, boolean inventoryOnly) {
        Minecraft client = Minecraft.getInstance();
        String needle = query.strip().toLowerCase(java.util.Locale.ROOT);
        var result = new ArrayList<ItemStack>();
        Iterable<ItemStack> candidates;
        if (inventoryOnly) {
            if (client.player == null) return List.of();
            candidates =
                    InventorySnapshot.capture(
                                    client.player.inventoryMenu, client.player.getInventory())
                            .items();
        } else {
            candidates = BuiltInRegistries.ITEM.stream().map(ItemStack::new).toList();
        }
        for (ItemStack stack : candidates) {
            if (stack.isEmpty()
                    || !itemId(stack).contains(needle)
                            && !stack.getHoverName()
                                    .getString()
                                    .toLowerCase(java.util.Locale.ROOT)
                                    .contains(needle)) continue;
            if (result.stream()
                    .noneMatch(existing -> ItemStack.isSameItemSameComponents(existing, stack)))
                result.add(stack.copy());
            if (result.size() == 60) break;
        }
        return List.copyOf(result);
    }

    private static DataComponentType<?> componentType(String key) {
        Identifier id = Identifier.tryParse(key);
        return id == null ? null : BuiltInRegistries.DATA_COMPONENT_TYPE.getValue(id);
    }

    private static <T> JsonElement encode(ItemStack stack, DataComponentType<T> type) {
        T value = stack.get(type);
        if (value == null) return JsonNull.INSTANCE;
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.level == null || type.codec() == null) return null;
        return type.codec()
                .encodeStart(
                        client.level.registryAccess().createSerializationContext(JsonOps.INSTANCE),
                        value)
                .result()
                .orElse(null);
    }

    private static <T> void applyComponent(ItemStack stack, String key, JsonElement value) {
        @SuppressWarnings("unchecked")
        DataComponentType<T> type = (DataComponentType<T>) componentType(key);
        if (type == null || type.codec() == null) return;
        if (value.isJsonNull()) {
            stack.remove(type);
            return;
        }
        type.codec()
                .parse(
                        Minecraft.getInstance()
                                .level
                                .registryAccess()
                                .createSerializationContext(JsonOps.INSTANCE),
                        value)
                .result()
                .ifPresent(component -> stack.set(type, component));
    }
}
