package com.blanoir.moons.client.ui.hud;

import net.minecraft.client.Minecraft;

import java.util.List;

/**
 * Renderer-independent HUD option binding used by module descriptors.
 * Rendering and editor state remain owned by {@link MoonsHud}.
 */
public final class HudOptions {
    private HudOptions() {}

    public static boolean isVisible() {
        return HudConfig.isVisible();
    }

    public static int setVisible(Minecraft client, boolean value) {
        return HudConfig.setVisible(client, value);
    }

    public static int setShowTitle(Minecraft ignoredClient, boolean value) {
        return HudConfig.setShowTitle(value);
    }

    public static String titleText() {
        return HudConfig.titleText();
    }

    public static int setTitle(Minecraft client, String value) {
        return HudConfig.setTitle(client, value);
    }

    public static int setTitleColor(Minecraft ignoredClient, String value) {
        return HudConfig.setTitleColor(value);
    }

    public static int setShowFps(Minecraft ignoredClient, boolean value) {
        return HudConfig.setShowFps(value);
    }

    public static List<String> fontModeOptions() {
        return HudConfig.fontModeOptions();
    }

    public static int setFontMode(Minecraft ignoredClient, String value) {
        return HudConfig.setFontMode(value);
    }

    public static int setTextShadowEnabled(Minecraft ignoredClient, boolean value) {
        return HudConfig.setTextShadowEnabled(value);
    }

    public static int setPanelOpacity(Minecraft ignoredClient, int value) {
        return HudConfig.setPanelOpacity(value);
    }

    public static int setUseThemeBackground(Minecraft ignoredClient, boolean value) {
        return HudConfig.setUseThemeBackground(value);
    }

    public static int setBackgroundColor(Minecraft ignoredClient, String value) {
        return HudConfig.setBackgroundColor(value);
    }

    public static int setScale(Minecraft ignoredClient, double value) {
        return HudConfig.setScale(value);
    }

    public static int setPositionX(Minecraft ignoredClient, int value) {
        return HudConfig.setPositionX(value);
    }

    public static int setPositionY(Minecraft ignoredClient, int value) {
        return HudConfig.setPositionY(value);
    }

    public static int setUseThemeColor(Minecraft ignoredClient, boolean value) {
        return HudConfig.setUseThemeColor(value);
    }

    public static List<String> nameColorModeOptions() {
        return HudConfig.nameColorModeOptions();
    }

    public static int setNameColorMode(Minecraft ignoredClient, String value) {
        return HudConfig.setNameColorMode(value);
    }

    public static List<String> gradientDirectionOptions() {
        return HudConfig.gradientDirectionOptions();
    }

    public static int setGradientDirection(Minecraft ignoredClient, String value) {
        return HudConfig.setGradientDirection(value);
    }

    public static int setColor(Minecraft ignoredClient, String value) {
        return HudConfig.setColor(value);
    }

    public static int setGradientColor(Minecraft ignoredClient, String value) {
        return HudConfig.setGradientColor(value);
    }

    public static int setParameterColor(Minecraft ignoredClient, String value) {
        return HudConfig.setParameterColor(value);
    }

    public static int setColorSpeed(Minecraft ignoredClient, double value) {
        return HudConfig.setColorSpeed(value);
    }

    public static int setColorSpread(Minecraft ignoredClient, double value) {
        return HudConfig.setColorSpread(value);
    }

    public static int setCharacterColorSpread(Minecraft ignoredClient, double value) {
        return HudConfig.setCharacterColorSpread(value);
    }

    public static int setAlpha(Minecraft ignoredClient, int value) {
        return HudConfig.setAlpha(value);
    }
}
