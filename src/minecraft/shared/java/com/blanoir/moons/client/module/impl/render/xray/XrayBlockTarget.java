package com.blanoir.moons.client.module.impl.render.xray;

import com.blanoir.moons.client.access.MinecraftClientAccess;
import com.blanoir.moons.client.config.Settings;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Arrays;

public enum XrayBlockTarget implements XrayTarget {
    DIAMOND("diamond", true, 0, 220, 255, Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE),
    GOLD(
            "gold",
            true,
            255,
            105,
            180,
            Blocks.GOLD_ORE,
            Blocks.DEEPSLATE_GOLD_ORE,
            Blocks.NETHER_GOLD_ORE),
    LAPIS("lapis", true, 0, 0, 139, Blocks.LAPIS_ORE, Blocks.DEEPSLATE_LAPIS_ORE),
    COPPER_BLOCK("copper_block", false, 184, 115, 51, MinecraftClientAccess.copperBlocks()),
    BOOK_SHELF("book_shelf", false, 255, 0, 0, Blocks.BOOKSHELF),
    END_PORTAL_FRAME("end_portal_frame", true, 255, 215, 0, Blocks.END_PORTAL_FRAME),
    CHEST("chest", true, 255, 215, 0, Blocks.CHEST, Blocks.TRAPPED_CHEST);

    private final String commandName;
    private final Block[] blocks;
    private final boolean defaultEnabled;
    private final String defaultColor;
    private boolean enabled;
    private int red;
    private int green;
    private int blue;

    XrayBlockTarget(
            String commandName,
            boolean defaultEnabled,
            int red,
            int green,
            int blue,
            Block... blocks) {
        this.commandName = commandName;
        this.defaultEnabled = defaultEnabled;
        this.defaultColor = String.format("#%02x%02x%02x", red, green, blue);
        this.enabled = Settings.getBoolean(configKey(commandName, "enabled"), defaultEnabled);
        this.red = Settings.getInt(configKey(commandName, "red"), red);
        this.green = Settings.getInt(configKey(commandName, "green"), green);
        this.blue = Settings.getInt(configKey(commandName, "blue"), blue);
        this.blocks = blocks;
    }

    public String commandName() {
        return commandName;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean defaultEnabled() {
        return defaultEnabled;
    }

    public String defaultColor() {
        return defaultColor;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        Settings.setBoolean(configKey("enabled"), enabled);
    }

    public int red() {
        return red;
    }

    public int green() {
        return green;
    }

    public int blue() {
        return blue;
    }

    public void setColor(int red, int green, int blue) {
        this.red = red;
        this.green = green;
        this.blue = blue;
        Settings.setInt(configKey("red"), red);
        Settings.setInt(configKey("green"), green);
        Settings.setInt(configKey("blue"), blue);
    }

    private String configKey(String valueName) {
        return configKey(commandName, valueName);
    }

    private static String configKey(String commandName, String valueName) {
        return "xray.target." + commandName + "." + valueName;
    }

    public boolean matches(Block block) {
        return Arrays.asList(blocks).contains(block);
    }

    public String rgbString() {
        return "rgb(" + red + ", " + green + ", " + blue + ")";
    }

    public String statusText() {
        return enabled ? "enabled" : "disabled";
    }

    public static XrayTarget findEnabledTarget(BlockState state) {
        XrayTarget plugin = PluginXrayTargets.find(state);
        if (plugin != null) return plugin;
        if (PluginXrayTargets.isRecognized(state)) return null;
        return findEnabledTarget(state.getBlock());
    }

    public static XrayTarget findEnabledTarget(Block block) {
        XrayTarget custom = CustomXrayTargets.findEnabledTarget(block);
        if (custom != null) return custom;
        for (XrayBlockTarget target : values()) {
            if (target.enabled && target.matches(block)) {
                return target;
            }
        }

        return null;
    }

    public static void setAllEnabled(boolean enabled) {
        for (XrayBlockTarget target : values()) {
            target.setEnabled(enabled);
        }
    }
}
