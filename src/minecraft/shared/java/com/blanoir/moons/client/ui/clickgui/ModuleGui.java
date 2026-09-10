package com.blanoir.moons.client.ui.clickgui;

import com.blanoir.moons.client.access.MinecraftClientAccess;
import com.blanoir.moons.client.config.Settings;

import net.minecraft.client.Minecraft;

import java.awt.Desktop;
import java.util.List;

/**
 * In-game entry point for the Compose/Skia module GUI.
 *
 * <p>The GUI key is handled by the standalone keyboard hook,
 * so no vanilla {@code KeyMapping} is registered here.
 */
public final class ModuleGui {
    private static MoonsComposeScreen cachedScreen;

    private ModuleGui() {}

    public static String layout() {
        return "panels".equals(Settings.getString("clickgui.layout", "settings"))
                ? "panels"
                : "settings";
    }

    public static List<String> layoutOptions() {
        return List.of("settings", "panels");
    }

    public static int setLayout(Minecraft ignoredClient, String layout) {
        if (!layoutOptions().contains(layout)) return 0;
        Settings.setString("clickgui.layout", layout);
        return 1;
    }

    /** Invoked only by the user's configuration-file button. Returns an inline error, if any. */
    public static String openConfigurationFile() {
        try {
            Settings.save();
            if (!Desktop.isDesktopSupported()
                    || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                return "Open this file manually: " + Settings.file();
            }
            Desktop.getDesktop().open(Settings.file().toFile());
            return "";
        } catch (Exception exception) {
            return "Could not open " + Settings.file() + ": " + exception.getMessage();
        }
    }

    public static void open(Minecraft client) {
        if (client.player == null || client.level == null) {
            return;
        }
        if (MinecraftClientAccess.screen(client) instanceof MoonsComposeScreen) {
            return;
        }
        ClickGuiWarmup.cancel();
        if (cachedScreen == null) cachedScreen = new MoonsComposeScreen();
        MinecraftClientAccess.setScreen(client, cachedScreen);
    }

    /** Releases the retained scene and GPU caches when the feature payload unloads. */
    public static void close() {
        ClickGuiWarmup.cancel();
        if (cachedScreen == null) return;
        cachedScreen.dispose();
        cachedScreen = null;
    }

    public static void toggle(Minecraft client) {
        if (MinecraftClientAccess.screen(client) instanceof MoonsComposeScreen) {
            MinecraftClientAccess.setScreen(client, null);
            return;
        }
        open(client);
    }

    public static boolean isOpen() {
        return MinecraftClientAccess.screen(Minecraft.getInstance()) instanceof MoonsComposeScreen;
    }

    public static int setEnabled(Minecraft client, boolean enabled) {
        if (enabled) {
            open(client);
        } else if (MinecraftClientAccess.screen(client) instanceof MoonsComposeScreen) {
            MinecraftClientAccess.setScreen(client, null);
        }
        return 1;
    }
}
