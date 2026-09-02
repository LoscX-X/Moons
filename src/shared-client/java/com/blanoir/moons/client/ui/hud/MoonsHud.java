package com.blanoir.moons.client.ui.hud;

import com.blanoir.moons.client.ui.layout.Bounds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.List;

/** Configuration and editor bridge for the independent Skia TextGUI layer. */
public final class MoonsHud {
    private static final MoonsHud INSTANCE = new MoonsHud();

    private Bounds lastBounds = new Bounds(0, 0, 0, 0);
    private double lastRenderedScale = HudConfig.SCALE.get();

    private MoonsHud() {
    }

    public static boolean isVisible() {
        return HudConfig.isVisible();
    }

    public static int setVisible(Minecraft client, boolean value) {
        return HudConfig.setVisible(client, value);
    }

    public static boolean isTextShadowEnabled() {
        return HudConfig.isTextShadowEnabled();
    }

    public static int setTextShadowEnabled(Minecraft client, boolean value) {
        return HudConfig.setTextShadowEnabled(client, value);
    }

    public static int setPanelOpacity(Minecraft client, int value) {
        return HudConfig.setPanelOpacity(client, value);
    }

    public static int setUseThemeBackground(Minecraft client, boolean value) {
        return HudConfig.setUseThemeBackground(client, value);
    }

    public static int setBackgroundColor(Minecraft client, String value) {
        return HudConfig.setBackgroundColor(client, value);
    }

    public static Bounds currentBounds() {
        return INSTANCE.lastBounds;
    }

    public static void renderForEditor(GuiGraphicsExtractor graphics) {
        // TextGUI is drawn later by TextGuiSkiaOverlay into the final framebuffer.
    }

    public static void updateExternalBounds(Bounds bounds, double renderedScale) {
        INSTANCE.lastBounds = bounds;
        INSTANCE.lastRenderedScale = renderedScale;
    }

    public static double scale() {
        return HudConfig.scale();
    }

    public static int setScale(Minecraft client, double value) {
        return HudConfig.setScale(client, value);
    }

    public static int setShowTitle(Minecraft client, boolean value) {
        return HudConfig.setShowTitle(client, value);
    }

    public static int setShowFps(Minecraft client, boolean value) {
        return HudConfig.setShowFps(client, value);
    }

    public static int setTitleColor(Minecraft client, String value) {
        return HudConfig.setTitleColor(client, value);
    }

    public static List<String> fontModeOptions() {
        return HudConfig.fontModeOptions();
    }

    public static int setFontMode(Minecraft client, String value) {
        return HudConfig.setFontMode(client, value);
    }

    public static void resizeForEditor(double value, double left, double top,
                                       int screenWidth, int screenHeight) {
        HudConfig.SCALE.set(value);
        double nextScale = HudConfig.SCALE.get();
        double ratio = INSTANCE.lastRenderedScale <= 0.0D
                ? 1.0D : nextScale / INSTANCE.lastRenderedScale;
        double width = INSTANCE.lastBounds.width() * ratio;
        double height = INSTANCE.lastBounds.height() * ratio;
        double clampedLeft = Math.max(0.0D, Math.min(screenWidth - width, left));
        double clampedTop = Math.max(0.0D, Math.min(screenHeight - height, top));
        HudConfig.POSITION_X.set((int) Math.round((clampedLeft + width) / nextScale));
        HudConfig.POSITION_Y.set((int) Math.round(clampedTop / nextScale));
    }

    public static int setUseThemeColor(Minecraft client, boolean value) {
        return HudConfig.setUseThemeColor(client, value);
    }

    public static int setColor(Minecraft client, String value) {
        return HudConfig.setColor(client, value);
    }

    public static List<String> nameColorModeOptions() {
        return HudConfig.nameColorModeOptions();
    }

    public static int setNameColorMode(Minecraft client, String value) {
        return HudConfig.setNameColorMode(client, value);
    }

    public static List<String> gradientDirectionOptions() {
        return HudConfig.gradientDirectionOptions();
    }

    public static int setGradientDirection(Minecraft client, String value) {
        return HudConfig.setGradientDirection(client, value);
    }

    public static int setGradientColor(Minecraft client, String value) {
        return HudConfig.setGradientColor(client, value);
    }

    public static int setParameterColor(Minecraft client, String value) {
        return HudConfig.setParameterColor(client, value);
    }

    public static int setColorSpeed(Minecraft client, double value) {
        return HudConfig.setColorSpeed(client, value);
    }

    public static int setColorSpread(Minecraft client, double value) {
        return HudConfig.setColorSpread(client, value);
    }

    public static int setCharacterColorSpread(Minecraft client, double value) {
        return HudConfig.setCharacterColorSpread(client, value);
    }

    public static int setAlpha(Minecraft client, int value) {
        return HudConfig.setAlpha(client, value);
    }

    public static int setPositionX(Minecraft client, int value) {
        return HudConfig.setPositionX(client, value);
    }

    public static int setPositionY(Minecraft client, int value) {
        return HudConfig.setPositionY(client, value);
    }

    public static void setEditorPosition(double left, double top, int screenWidth, int screenHeight) {
        double scale = HudConfig.SCALE.get();
        Bounds current = INSTANCE.lastBounds;
        double clampedLeft = Math.max(0.0D, Math.min(screenWidth - current.width(), left));
        double clampedTop = Math.max(0.0D, Math.min(screenHeight - current.height(), top));
        HudConfig.POSITION_X.set((int) Math.round((clampedLeft + current.width()) / scale));
        HudConfig.POSITION_Y.set((int) Math.round(clampedTop / scale));
    }

    public static void resetEditorPosition() {
        HudConfig.POSITION_X.set(-1);
        HudConfig.POSITION_Y.set(5);
        HudConfig.SCALE.set(0.78D);
    }

    public static int setTitle(Minecraft client, String value) {
        return HudConfig.setTitle(client, value);
    }

    public static int showTitle(Minecraft client) {
        return HudConfig.showTitle(client);
    }

    public static String titleText() {
        return HudConfig.titleText();
    }
}
