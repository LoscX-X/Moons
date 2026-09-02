package com.blanoir.moons.features.command;

import com.blanoir.moons.client.module.impl.render.xray.CustomXrayTargets;
import com.blanoir.moons.client.module.impl.render.xray.OreCache;
import com.blanoir.moons.client.module.impl.render.xray.OreScanner;
import com.blanoir.moons.client.chat.ClientChat;
import net.minecraft.client.Minecraft;

import java.util.Locale;
import java.util.Map;

/** Implements the local `.xray ore` command. */
final class XrayCommand {
    private static final CustomXrayTargets.ColorValue DEFAULT_COLOR =
            new CustomXrayTargets.ColorValue(0, 220, 255);
    private static final Map<String, CustomXrayTargets.ColorValue> COLORS = Map.ofEntries(
            Map.entry("red", color(255, 0, 0)), Map.entry("green", color(0, 255, 0)),
            Map.entry("blue", color(0, 0, 255)), Map.entry("cyan", DEFAULT_COLOR),
            Map.entry("aqua", color(0, 255, 255)), Map.entry("pink", color(255, 105, 180)),
            Map.entry("purple", color(143, 0, 226)), Map.entry("gold", color(255, 215, 0)),
            Map.entry("yellow", color(255, 255, 0)), Map.entry("orange", color(239, 128, 2)),
            Map.entry("white", color(255, 255, 255)), Map.entry("black", color(0, 0, 0)));

    private XrayCommand() { }

    static boolean handle(String tail) {
        String[] parts = tail.split("\\s+", 4);
        if (parts.length >= 3 && parts[0].equalsIgnoreCase("ore")) {
            if (parts[1].equalsIgnoreCase("add")) {
                add(parts[2], parts.length == 4 ? parseColor(parts[3]) : DEFAULT_COLOR);
                return true;
            }
            if (parts[1].equalsIgnoreCase("remove")) {
                remove(parts[2]);
                return true;
            }
        }
        ClientChat.send(Minecraft.getInstance(),
                "Usage: .xray ore <add block [color]|remove block>");
        return true;
    }

    private static void add(String blockName, CustomXrayTargets.ColorValue color) {
        Minecraft client = Minecraft.getInstance();
        if (color == null) {
            ClientChat.send(client, "Invalid color. Use a preset, #RRGGBB, r,g,b, or rgb(r,g,b).");
            return;
        }
        CustomXrayTargets.AddResult result = CustomXrayTargets.add(blockName, color);
        if (!result.success()) {
            ClientChat.send(client, result.invalidBlock()
                    ? "Invalid block id: " + blockName + "."
                    : "Unknown block: " + result.id() + ".");
            return;
        }
        OreScanner.requestFullRescan(client);
        ClientChat.send(client, "Ore target added: "
                + result.target().commandName() + " " + result.target().rgbString() + ".");
    }

    private static void remove(String blockName) {
        Minecraft client = Minecraft.getInstance();
        if (!CustomXrayTargets.remove(blockName)) {
            ClientChat.send(client, "Ore target not found: " + blockName + ".");
            return;
        }
        OreCache.removeInvalidPositions(client);
        OreScanner.requestFullRescan(client);
        ClientChat.send(client, "Ore target removed: " + blockName + ".");
    }

    private static CustomXrayTargets.ColorValue parseColor(String raw) {
        String value = raw.trim().toLowerCase(Locale.ROOT);
        CustomXrayTargets.ColorValue preset = COLORS.get(value);
        if (preset != null) return preset;
        if (value.startsWith("#")) value = value.substring(1);
        else if (value.startsWith("0x")) value = value.substring(2);
        if (value.matches("[0-9a-f]{6}")) {
            int rgb = Integer.parseInt(value, 16);
            return color((rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255);
        }
        if (value.startsWith("rgb(") && value.endsWith(")")) {
            value = value.substring(4, value.length() - 1);
        }
        String[] components = value.split("[, ]+");
        if (components.length != 3) return null;
        try {
            int red = Integer.parseInt(components[0]);
            int green = Integer.parseInt(components[1]);
            int blue = Integer.parseInt(components[2]);
            return valid(red) && valid(green) && valid(blue) ? color(red, green, blue) : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static boolean valid(int value) { return value >= 0 && value <= 255; }
    private static CustomXrayTargets.ColorValue color(int red, int green, int blue) {
        return new CustomXrayTargets.ColorValue(red, green, blue);
    }
}
