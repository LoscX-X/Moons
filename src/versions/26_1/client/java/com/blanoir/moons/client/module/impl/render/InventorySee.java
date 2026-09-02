package com.blanoir.moons.client.module.impl.render;

import com.blanoir.moons.client.config.settings.BooleanSetting;
import com.blanoir.moons.client.config.settings.DoubleSetting;
import com.blanoir.moons.client.config.settings.IntSetting;
import com.blanoir.moons.client.ui.layout.Bounds;
import com.blanoir.moons.client.chat.ClientChat;
import net.minecraft.client.Minecraft;

/** Inventory state/configuration for the independent final-frame Skia HUD layer. */
public final class InventorySee {
    private static final int WIDTH = 188;
    private static final int HEIGHT = 68;

    private static final BooleanSetting ENABLED =
            new BooleanSetting.Builder()
                    .name("inventorysee.enabled")
                    .defaultValue(false)
                    .build();
    private static final IntSetting POSITION_X =
            new IntSetting.Builder()
                    .name("inventorysee.x")
                    .defaultValue(16)
                    .range(0, 10000)
                    .build();
    private static final IntSetting POSITION_Y =
            new IntSetting.Builder()
                    .name("inventorysee.y")
                    .defaultValue(42)
                    .range(0, 10000)
                    .build();
    private static final DoubleSetting SCALE =
            new DoubleSetting.Builder()
                    .name("inventorysee.scale")
                    .defaultValue(1.0D)
                    .range(0.25D, 2.0D)
                    .build();

    private InventorySee() {
    }

    public static void init() {
        // Rendering is intentionally owned by TextGuiSkiaOverlay. Do not register HUD_RENDER here.
    }

    public static boolean isEnabled() {
        return ENABLED.get();
    }

    public static int setEnabled(Minecraft client, boolean enabled) {
        ENABLED.set(enabled);
        ClientChat.send(client, "InventorySee " + (enabled ? "enabled" : "disabled") + ".");
        return 1;
    }

    public static Bounds currentBounds() {
        Minecraft client = Minecraft.getInstance();
        double scale = scale();
        double width = WIDTH * scale;
        double height = HEIGHT * scale;
        double left = Math.max(0.0D, Math.min(POSITION_X.get(), client.getWindow().getGuiScaledWidth() - width));
        double top = Math.max(0.0D, Math.min(POSITION_Y.get(), client.getWindow().getGuiScaledHeight() - height));
        return new Bounds(left, top, width, height);
    }

    public static void setEditorPosition(double left, double top, int screenWidth, int screenHeight) {
        double width = WIDTH * scale();
        double height = HEIGHT * scale();
        int x = (int) Math.round(Math.max(0.0D, Math.min(screenWidth - width, left)));
        int y = (int) Math.round(Math.max(0.0D, Math.min(screenHeight - height, top)));
        POSITION_X.set(x);
        POSITION_Y.set(y);
    }

    public static double scale() {
        return crispScale(SCALE.get());
    }

    public static int setScale(Minecraft client, double value) {
        SCALE.set(crispScale(value));
        return 1;
    }

    public static void resizeForEditor(double scale, double left, double top,
                                       int screenWidth, int screenHeight) {
        SCALE.set(crispScale(scale));
        setEditorPosition(left, top, screenWidth, screenHeight);
    }

    public static void resetEditorPosition() {
        POSITION_X.set(16);
        POSITION_Y.set(42);
        SCALE.set(1.0D);
    }

    private static double crispScale(double requested) {
        int guiScale = Math.max(1, Minecraft.getInstance().getWindow().getGuiScale());
        double physicalScale = Math.max(1.0D, Math.rint(requested * guiScale));
        return Math.min(2.0D, physicalScale / guiScale);
    }

}
