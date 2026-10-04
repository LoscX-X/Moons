package com.blanoir.moons.client.module.impl.player.invmanager;

import com.blanoir.moons.client.config.Settings;
import com.blanoir.moons.client.utils.inventory.LegacyItems;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonPrimitive;

import net.minecraft.client.Minecraft;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.JsonToNBT;
import net.minecraft.util.ResourceLocation;

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
    public static final List<String> SAMPLE_FIELDS = List.of("damage", "nbt");
    private static String loaded;
    private static State state = new State(1, List.of());
    private static String error = "";

    public record Entry(
            String item,
            Map<String, JsonElement> components,
            boolean allowSpecial,
            Map<String, JsonElement> sample) {
        public Entry {
            if (item == null || parseId(item) == null)
                throw new IllegalArgumentException("Invalid item ID");
            components = components == null ? Map.of() : Map.copyOf(components);
            sample = sample == null ? components : Map.copyOf(sample);
        }

        public boolean matches(ItemStack stack) {
            if (LegacyItems.empty(stack) || !item.equals(itemId(stack)) || !available())
                return false;
            for (var component : components.entrySet()) {
                var type = componentType(component.getKey());
                if (type == null) return false;
                JsonElement actual = encode(stack, type);
                if (actual == null || !actual.equals(component.getValue())) return false;
            }
            return true;
        }

        public boolean available() {
            if (!Item.itemRegistry.containsKey(parseId(item))) return false;
            for (String key : components.keySet()) if (componentType(key) == null) return false;
            return true;
        }

        public boolean authorizesSpecial(ItemStack stack) {
            // Explicit samples must cover every field inspected by Protect special. A potion
            // predicate alone must not authorize every named server item using that potion ID.
            return allowSpecial
                    && !components.isEmpty()
                    && matches(stack)
                    && components.containsKey("nbt");
        }

        public ItemStack icon() {
            ResourceLocation id = parseId(item);
            if (!Item.itemRegistry.containsKey(id)) return LegacyItems.EMPTY;
            ItemStack stack = new ItemStack(Item.itemRegistry.getObject(id));
            Minecraft client = Minecraft.getMinecraft();
            if (client.theWorld != null) {
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
            if (LegacyItems.empty(stack)) return false;
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
            if (LegacyItems.empty(stack)
                    || !protectSpecial
                    || InventoryItems.protection(stack).isEmpty()) return true;
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
            if (LegacyItems.empty(stack)) return "Empty";
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
        return Item.itemRegistry.getNameForObject(stack.getItem()).toString();
    }

    public static List<ItemStack> search(String query, boolean inventoryOnly) {
        Minecraft client = Minecraft.getMinecraft();
        String needle = query.strip().toLowerCase(java.util.Locale.ROOT);
        var result = new ArrayList<ItemStack>();
        Iterable<ItemStack> candidates;
        if (inventoryOnly) {
            if (client.thePlayer == null) return List.of();
            candidates =
                    InventorySnapshot.capture(
                                    client.thePlayer.inventoryContainer, client.thePlayer.inventory)
                            .items();
        } else {
            var all = new ArrayList<ItemStack>();
            for (Item item : Item.itemRegistry) all.add(new ItemStack(item));
            candidates = all;
        }
        for (ItemStack stack : candidates) {
            if (LegacyItems.empty(stack)
                    || !itemId(stack).contains(needle)
                            && !stack.getDisplayName()
                                    .toLowerCase(java.util.Locale.ROOT)
                                    .contains(needle)) continue;
            if (result.stream().noneMatch(existing -> LegacyItems.same(existing, stack)))
                result.add(LegacyItems.copy(stack));
            if (result.size() == 60) break;
        }
        return List.copyOf(result);
    }

    private static ResourceLocation parseId(String value) {
        if (value == null || !value.matches("(?:[a-z0-9_.-]+:)?[a-z0-9_./-]+")) return null;
        return new ResourceLocation(value);
    }

    private static String componentType(String key) {
        return SAMPLE_FIELDS.contains(key) ? key : null;
    }

    private static JsonElement encode(ItemStack stack, String field) {
        if (field.equals("damage")) return new JsonPrimitive(stack.getMetadata());
        if (field.equals("nbt"))
            return stack.hasTagCompound()
                    ? new JsonPrimitive(stack.getTagCompound().toString())
                    : JsonNull.INSTANCE;
        return null;
    }

    private static void applyComponent(ItemStack stack, String field, JsonElement value) {
        if (field.equals("damage")) {
            if (!value.isJsonNull()) stack.setItemDamage(value.getAsInt());
            return;
        }
        if (!field.equals("nbt")) return;
        if (value.isJsonNull()) {
            stack.setTagCompound(null);
            return;
        }
        try {
            stack.setTagCompound(JsonToNBT.getTagFromJson(value.getAsString()));
        } catch (net.minecraft.nbt.NBTException invalid) {
            throw new IllegalArgumentException("Invalid NBT sample", invalid);
        }
    }
}
