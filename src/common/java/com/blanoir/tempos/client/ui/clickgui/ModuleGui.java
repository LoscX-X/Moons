package com.blanoir.tempos.client.ui.clickgui;

import com.blanoir.tempos.client.ui.host.UiHost;
import com.blanoir.tempos.client.config.Settings;

import net.minecraft.client.Minecraft;

import java.awt.Desktop;

/**
 * Compatibility entry for existing module, command and lifecycle callers.
 *
 * <p>The GUI key is handled by the standalone keyboard hook,
 * so no vanilla {@code KeyMapping} is registered here.
 */
public final class ModuleGui {
    private ModuleGui() {}

    /** Invoked only by the user's configuration-file button. Returns an inline error, if any. */
    public static String openConfigurationFile() {
        try {
            Settings.save();
            if (!Settings.saveResult().saved())
                return "Could not save " + Settings.file() + ": " + Settings.saveResult().error();
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

    public static void open(Minecraft client) { UiHost.open(client, UiHost.Page.CLICKGUI); }

    public static void toggle(Minecraft client) {
        if (UiHost.isOpen(client)) UiHost.close(client);
        else open(client);
    }

    public static boolean isOpen() { return UiHost.isOpen(Minecraft.getInstance()); }

    public static int setEnabled(Minecraft client, boolean enabled) {
        if (enabled) open(client);
        else UiHost.close(client);
        return 1;
    }
}
