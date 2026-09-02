package com.blanoir.moons.client.ui.hud;

import net.minecraft.client.Minecraft;

import java.util.List;

/**
 * Renderer-independent HUD option binding used by module descriptors.
 * Rendering and editor state remain owned by {@link MoonsHud}.
 */
public final class HudOptions {
    private HudOptions() {
    }

    public static boolean isVisible() { return HudConfig.isVisible(); }
    public static int setVisible(Minecraft client, boolean value) {
        return HudConfig.setVisible(client, value);
    }
    public static int setShowTitle(Minecraft client, boolean value) {
        return HudConfig.setShowTitle(client, value);
    }
    public static String titleText() { return HudConfig.titleText(); }
    public static int setTitle(Minecraft client, String value) {
        return HudConfig.setTitle(client, value);
    }
    public static int setTitleColor(Minecraft client, String value) {
        return HudConfig.setTitleColor(client, value);
    }
    public static int setShowFps(Minecraft client, boolean value) {
        return HudConfig.setShowFps(client, value);
    }
    public static List<String> fontModeOptions() { return HudConfig.fontModeOptions(); }
    public static int setFontMode(Minecraft client, String value) {
        return HudConfig.setFontMode(client, value);
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
    public static int setScale(Minecraft client, double value) {
        return HudConfig.setScale(client, value);
    }
    public static int setPositionX(Minecraft client, int value) {
        return HudConfig.setPositionX(client, value);
    }
    public static int setPositionY(Minecraft client, int value) {
        return HudConfig.setPositionY(client, value);
    }
    public static int setUseThemeColor(Minecraft client, boolean value) {
        return HudConfig.setUseThemeColor(client, value);
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
    public static int setColor(Minecraft client, String value) {
        return HudConfig.setColor(client, value);
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
}
