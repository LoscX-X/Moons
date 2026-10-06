package com.blanoir.moons.client.ui.hud;

import com.blanoir.moons.client.ui.layout.Bounds;

import net.minecraft.client.Minecraft;

import java.util.List;

/** Configuration and editor bridge for the independent Skia TextGUI layer. */
public final class MoonsHud {
    private static final MoonsHud INSTANCE = new MoonsHud();

    private Bounds lastBounds = new Bounds(0, 0, 0, 0);
    private double lastRenderedScale = HudConfig.SCALE.get();

    private MoonsHud() {}

    public static boolean isVisible() {
        return HudConfig.isVisible();
    }

    public static int setVisible(Minecraft client, boolean value) {
        return HudConfig.setVisible(client, value);
    }

    public static boolean isTextShadowEnabled() {
        return HudConfig.isTextShadowEnabled();
    }

    public static int setTextShadowEnabled(boolean value) {
        return HudConfig.setTextShadowEnabled(value);
    }

    public static int setPanelOpacity(int value) {
        return HudConfig.setPanelOpacity(value);
    }

    public static int setUseThemeBackground(boolean value) {
        return HudConfig.setUseThemeBackground(value);
    }

    public static int setBackgroundColor(String value) {
        return HudConfig.setBackgroundColor(value);
    }

    public static Bounds currentBounds() {
        return INSTANCE.lastBounds;
    }

    public static void updateExternalBounds(Bounds bounds, double renderedScale) {
        INSTANCE.lastBounds = bounds;
        INSTANCE.lastRenderedScale = renderedScale;
    }

    public static double scale() {
        return HudConfig.scale();
    }

    public static int setScale(double value) {
        return HudConfig.setScale(value);
    }

    public static int setShowTitle(boolean value) {
        return HudConfig.setShowTitle(value);
    }

    public static int setShowFps(boolean value) {
        return HudConfig.setShowFps(value);
    }

    public static int setTitleColor(String value) {
        return HudConfig.setTitleColor(value);
    }

    public static List<String> fontModeOptions() {
        return HudConfig.fontModeOptions();
    }

    public static int setFontMode(String value) {
        return HudConfig.setFontMode(value);
    }

    public static void resizeForEditor(
            double value, double left, double top, int screenWidth, int screenHeight) {
        HudConfig.SCALE.set(value);
        double nextScale = HudConfig.SCALE.get();
        double ratio =
                INSTANCE.lastRenderedScale <= 0.0D ? 1.0D : nextScale / INSTANCE.lastRenderedScale;
        double width = INSTANCE.lastBounds.width() * ratio;
        double height = INSTANCE.lastBounds.height() * ratio;
        double clampedLeft = Math.max(0.0D, Math.min(screenWidth - width, left));
        double clampedTop = Math.max(0.0D, Math.min(screenHeight - height, top));
        HudConfig.POSITION_X.set((int) Math.round((clampedLeft + width) / nextScale));
        HudConfig.POSITION_Y.set((int) Math.round(clampedTop / nextScale));
    }

    public static int setUseThemeColor(boolean value) {
        return HudConfig.setUseThemeColor(value);
    }

    public static int setColor(String value) {
        return HudConfig.setColor(value);
    }

    public static List<String> nameColorModeOptions() {
        return HudConfig.nameColorModeOptions();
    }

    public static int setNameColorMode(String value) {
        return HudConfig.setNameColorMode(value);
    }

    public static List<String> gradientDirectionOptions() {
        return HudConfig.gradientDirectionOptions();
    }

    public static int setGradientDirection(String value) {
        return HudConfig.setGradientDirection(value);
    }

    public static int setGradientColor(String value) {
        return HudConfig.setGradientColor(value);
    }

    public static int setParameterColor(String value) {
        return HudConfig.setParameterColor(value);
    }

    public static int setColorSpeed(double value) {
        return HudConfig.setColorSpeed(value);
    }

    public static int setColorSpread(double value) {
        return HudConfig.setColorSpread(value);
    }

    public static int setCharacterColorSpread(double value) {
        return HudConfig.setCharacterColorSpread(value);
    }

    public static int setAlpha(int value) {
        return HudConfig.setAlpha(value);
    }

    public static int setPositionX(int value) {
        return HudConfig.setPositionX(value);
    }

    public static int setPositionY(int value) {
        return HudConfig.setPositionY(value);
    }

    public static void setEditorPosition(
            double left, double top, int screenWidth, int screenHeight) {
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
