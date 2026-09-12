package com.blanoir.moons.client.module.impl.render.xray;

import com.blanoir.moons.client.config.Settings;
import com.blanoir.moons.client.utils.registry.RegistryLists;

import net.minecraft.IdentifierException;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

public final class CustomXrayTargets {
    private static final String CONFIG_TARGETS = "xray.custom.targets";
    private static final Map<Identifier, CustomTarget> TARGETS = loadTargets();

    private CustomXrayTargets() {}

    public static synchronized AddResult add(String blockName, ColorValue color) {
        Identifier id = normalizeBlockId(blockName);

        if (id == null) {
            return invalidBlock();
        }

        Block block = blockById(id);
        if (block == null) {
            return unknownBlock(id);
        }

        CustomTarget target =
                new CustomTarget(id, block, true, color.red(), color.green(), color.blue());
        TARGETS.put(id, target);
        saveTargets();
        return added(target);
    }

    public static synchronized boolean remove(String blockName) {
        Identifier id = normalizeBlockId(blockName);

        if (id == null) {
            return false;
        }

        CustomTarget removedTarget = TARGETS.remove(id);

        if (removedTarget != null) {
            removedTarget.setEnabled(false);
            saveTargets();
        }

        return removedTarget != null;
    }

    public static synchronized AddResult setColor(String blockName, ColorValue color) {
        Identifier id = normalizeBlockId(blockName);

        if (id == null) {
            return invalidBlock();
        }

        CustomTarget target = TARGETS.get(id);
        if (target == null) {
            Block block = blockById(id);
            if (block == null) {
                return unknownBlock(id);
            }

            target = new CustomTarget(id, block, true, color.red(), color.green(), color.blue());
            TARGETS.put(id, target);
        } else {
            target.setColor(color.red(), color.green(), color.blue());
        }

        saveTargets();
        return added(target);
    }

    public static synchronized XrayTarget findEnabledTarget(Block block) {
        for (CustomTarget target : TARGETS.values()) {
            if (target.isEnabled() && target.matches(block)) {
                return target;
            }
        }

        return null;
    }

    public static synchronized List<CustomTarget> snapshot() {
        return new ArrayList<>(TARGETS.values());
    }

    public static synchronized com.google.gson.JsonArray selectedTargets() {
        var result = new com.google.gson.JsonArray();
        for (CustomTarget target : TARGETS.values()) {
            var entry =
                    RegistryLists.entry(
                            target.id().toString(),
                            String.format(
                                    "#%02x%02x%02x", target.red(), target.green(), target.blue()));
            entry.addProperty("enabled", target.isEnabled());
            result.add(entry);
        }
        return result;
    }

    public static synchronized void setTargets(
            net.minecraft.client.Minecraft client, com.google.gson.JsonElement value) {
        if (!RegistryLists.valid("block_list", value))
            throw new IllegalArgumentException("Invalid block list");
        Map<Identifier, CustomTarget> next = new LinkedHashMap<>();
        boolean changed = false;
        for (var element : value.getAsJsonArray()) {
            var entry = element.getAsJsonObject();
            Identifier id = Identifier.parse(entry.get("id").getAsString());
            int color = Integer.parseInt(entry.get("color").getAsString().substring(1), 16);
            CustomTarget target = TARGETS.get(id);
            changed |= target == null || target.isEnabled() != entry.get("enabled").getAsBoolean();
            if (target == null) target = new CustomTarget(id, blockById(id), true, 0, 0, 0);
            target.setEnabled(entry.get("enabled").getAsBoolean());
            target.setColor(color >> 16 & 255, color >> 8 & 255, color & 255);
            next.put(id, target);
        }
        changed |= !TARGETS.keySet().equals(next.keySet());
        TARGETS.forEach(
                (id, target) -> {
                    if (!next.containsKey(id)) target.setEnabled(false);
                });
        TARGETS.clear();
        TARGETS.putAll(next);
        saveTargets();
        if (changed && client != null && client.level != null) {
            OreCache.removeInvalidPositions(client);
            OreScanner.requestFullRescan(client);
        }
    }

    public static synchronized void setAllEnabled(boolean enabled) {
        for (CustomTarget target : TARGETS.values()) {
            target.setEnabled(enabled);
        }

        saveTargets();
    }

    private static Map<Identifier, CustomTarget> loadTargets() {
        Map<Identifier, CustomTarget> targets = new LinkedHashMap<>();
        String raw = Settings.getString(CONFIG_TARGETS, "");

        Arrays.stream(raw.split(";"))
                .map(String::trim)
                .filter(entry -> !entry.isEmpty())
                .forEach(entry -> loadTarget(entry, targets));

        return targets;
    }

    private static void loadTarget(String entry, Map<Identifier, CustomTarget> targets) {
        String[] parts = entry.split(",", 5);
        if (parts.length != 5) {
            return;
        }

        Identifier id = normalizeBlockId(parts[0]);
        Block block = id == null ? null : blockById(id);
        if (id == null || block == null) {
            return;
        }

        try {
            boolean enabled = Boolean.parseBoolean(parts[1]);
            int red = clamp(Integer.parseInt(parts[2]));
            int green = clamp(Integer.parseInt(parts[3]));
            int blue = clamp(Integer.parseInt(parts[4]));
            targets.put(id, new CustomTarget(id, block, enabled, red, green, blue));
        } catch (NumberFormatException ignored) {
        }
    }

    private static void saveTargets() {
        Settings.setString(
                CONFIG_TARGETS,
                TARGETS.values().stream()
                        .map(
                                target ->
                                        target.id()
                                                + ","
                                                + target.isEnabled()
                                                + ","
                                                + target.red()
                                                + ","
                                                + target.green()
                                                + ","
                                                + target.blue())
                        .collect(Collectors.joining(";")));
    }

    private static Identifier normalizeBlockId(String blockName) {
        String normalized = blockName.trim().toLowerCase(Locale.ROOT);

        if (!normalized.contains(":")) {
            normalized = "minecraft:" + normalized;
        }

        try {
            return Identifier.parse(normalized);
        } catch (IdentifierException exception) {
            return null;
        }
    }

    private static Block blockById(Identifier id) {
        return BuiltInRegistries.BLOCK.getOptional(id).orElse(null);
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }

    public record ColorValue(int red, int green, int blue) {}

    public record AddResult(
            boolean success, Identifier id, CustomTarget target, boolean invalidBlock) {}

    private static AddResult added(CustomTarget target) {
        return new AddResult(true, target.id(), target, false);
    }

    private static AddResult invalidBlock() {
        return new AddResult(false, null, null, true);
    }

    private static AddResult unknownBlock(Identifier id) {
        return new AddResult(false, id, null, false);
    }

    public static final class CustomTarget implements XrayTarget {
        private final Identifier id;
        private final Block block;
        private boolean enabled;
        private int red;
        private int green;
        private int blue;

        private CustomTarget(
                Identifier id, Block block, boolean enabled, int red, int green, int blue) {
            this.id = id;
            this.block = block;
            this.enabled = enabled;
            this.red = red;
            this.green = green;
            this.blue = blue;
        }

        public Identifier id() {
            return id;
        }

        @Override
        public String commandName() {
            return id.toString();
        }

        @Override
        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        @Override
        public int red() {
            return red;
        }

        @Override
        public int green() {
            return green;
        }

        @Override
        public int blue() {
            return blue;
        }

        public void setColor(int red, int green, int blue) {
            this.red = red;
            this.green = green;
            this.blue = blue;
        }

        @Override
        public boolean matches(Block block) {
            return this.block == block;
        }

        public String rgbString() {
            return "rgb(" + red + ", " + green + ", " + blue + ")";
        }
    }
}
