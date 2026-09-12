package com.blanoir.moons.client.utils.plugin;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** Client-visible appearance selector; it does not claim to identify a server plugin ID. */
public final class PluginBlockSelector {
    private final Block block;
    private final Map<String, String> properties;
    private final String key;

    private PluginBlockSelector(Block block, Map<String, String> properties) {
        this.block = block;
        this.properties = Collections.unmodifiableMap(new TreeMap<>(properties));
        this.key =
                BuiltInRegistries.BLOCK.getKey(block)
                        + "["
                        + this.properties.entrySet().stream()
                                .map(entry -> entry.getKey() + "=" + entry.getValue())
                                .collect(Collectors.joining(","))
                        + "]";
    }

    public static PluginBlockSelector capture(BlockState state) {
        Map<String, String> values = new TreeMap<>();
        for (Property<?> property : state.getProperties()) {
            values.put(property.getName(), propertyValue(state, property));
        }
        return new PluginBlockSelector(state.getBlock(), values);
    }

    public static PluginBlockSelector parse(String input) {
        String text = input.trim();
        int bracket = text.indexOf('[');
        if (bracket <= 0 || !text.endsWith("]")) {
            throw new IllegalArgumentException("Use block[property=value,...] or looking.");
        }
        Identifier id = Identifier.tryParse(text.substring(0, bracket));
        Block block = id == null ? null : BuiltInRegistries.BLOCK.getOptional(id).orElse(null);
        if (block == null)
            throw new IllegalArgumentException("Unknown block: " + text.substring(0, bracket));
        if (block.defaultBlockState().isAir())
            throw new IllegalArgumentException("Air cannot be a plugin target.");
        Map<String, String> values = new TreeMap<>();
        String body = text.substring(bracket + 1, text.length() - 1).trim();
        if (!body.isEmpty()) {
            for (String part : body.split(",", -1)) {
                String[] pair = part.trim().split("=", -1);
                if (pair.length != 2)
                    throw new IllegalArgumentException("Invalid property: " + part);
                String name = pair[0].trim();
                String value = pair[1].trim();
                Property<?> property = block.getStateDefinition().getProperty(name);
                if (property == null || property.getValue(value).isEmpty()) {
                    throw new IllegalArgumentException("Invalid property or value: " + part);
                }
                if (values.putIfAbsent(name, value) != null) {
                    throw new IllegalArgumentException("Duplicate property: " + name);
                }
            }
        }
        if (values.isEmpty() && !block.getStateDefinition().getProperties().isEmpty()) {
            throw new IllegalArgumentException(
                    "Specify at least one state property, or use looking.");
        }
        return new PluginBlockSelector(block, values);
    }

    public boolean matches(BlockState state) {
        if (state.getBlock() != block) return false;
        for (var entry : properties.entrySet()) {
            Property<?> property = block.getStateDefinition().getProperty(entry.getKey());
            if (!entry.getValue().equals(propertyValue(state, property))) return false;
        }
        return true;
    }

    public Block block() {
        return block;
    }

    public int specificity() {
        return properties.size();
    }

    public String key() {
        return key;
    }

    private static <T extends Comparable<T>> String propertyValue(
            BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }
}
