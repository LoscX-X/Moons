package com.blanoir.moons.client.ui.hud;

import com.blanoir.moons.client.chat.ClientChat;
import com.blanoir.moons.client.config.ClientBranding;
import com.blanoir.moons.client.config.settings.BooleanSetting;
import com.blanoir.moons.client.config.settings.DoubleSetting;
import com.blanoir.moons.client.config.settings.IntSetting;
import com.blanoir.moons.client.config.settings.ModeSetting;
import com.blanoir.moons.client.config.settings.StringSetting;
import com.blanoir.moons.client.utils.render.ColorCodec;

import net.minecraft.client.Minecraft;

import java.util.List;

/** Persistent HUD options and their command-facing mutations. */
final class HudConfig {
    private HudConfig() {}

    enum NameColorMode {
        FIXED,
        GRADIENT,
        RAINBOW
    }

    enum GradientDirection {
        HORIZONTAL,
        TOP_TO_BOTTOM,
        BOTTOM_TO_TOP
    }

    enum HudFontMode {
        ORIGINAL,
        MINECRAFT,
        SMOOTH
    }

    static final BooleanSetting VISIBLE =
            new BooleanSetting.Builder().name("featurehud.enabled").defaultValue(true).build();

    static final BooleanSetting TEXT_SHADOW =
            new BooleanSetting.Builder().name("featurehud.textShadow").defaultValue(true).build();

    static final IntSetting PANEL_OPACITY =
            new IntSetting.Builder()
                    .name("featurehud.panelOpacity")
                    .defaultValue(35)
                    .range(0, 100)
                    .build();

    static final BooleanSetting USE_THEME_BACKGROUND =
            new BooleanSetting.Builder()
                    .name("featurehud.useThemeBackground")
                    .defaultValue(true)
                    .build();

    static final StringSetting BACKGROUND_COLOR =
            new StringSetting.Builder()
                    .name("featurehud.backgroundColor")
                    .defaultValue("#2d2723")
                    .build();

    static final DoubleSetting SCALE =
            new DoubleSetting.Builder()
                    .name("featurehud.scale")
                    .defaultValue(0.78D)
                    .range(0.5D, 2.0D)
                    .build();

    /** -1 keeps the historic responsive right alignment until the user drags the HUD. */
    static final IntSetting POSITION_X =
            new IntSetting.Builder().name("featurehud.x").defaultValue(-1).range(-1, 10000).build();

    static final IntSetting POSITION_Y =
            new IntSetting.Builder().name("featurehud.y").defaultValue(5).range(0, 10000).build();

    static final BooleanSetting USE_THEME_COLOR =
            new BooleanSetting.Builder()
                    .name("featurehud.useThemeColor")
                    .defaultValue(true)
                    .build();

    static final StringSetting COLOR =
            new StringSetting.Builder().name("featurehud.color").defaultValue("#c49a6c").build();

    static final BooleanSetting SHOW_TITLE =
            new BooleanSetting.Builder().name("featurehud.showTitle").defaultValue(true).build();

    static final StringSetting TITLE =
            new StringSetting.Builder()
                    .name("featurehud.title")
                    .defaultValue(ClientBranding.name())
                    .build();

    static final StringSetting TITLE_COLOR =
            new StringSetting.Builder()
                    .name("featurehud.titleColor")
                    .defaultValue("#ffffff")
                    .build();

    static final BooleanSetting SHOW_FPS =
            new BooleanSetting.Builder().name("featurehud.showFps").defaultValue(true).build();

    static final ModeSetting<HudFontMode> FONT_MODE =
            new ModeSetting.Builder<HudFontMode>()
                    .name("featurehud.fontMode")
                    .defaultValue(HudFontMode.SMOOTH)
                    .option(HudFontMode.ORIGINAL, "original")
                    .option(HudFontMode.MINECRAFT, "minecraft")
                    .option(HudFontMode.SMOOTH, "smooth")
                    .build();

    static final ModeSetting<NameColorMode> NAME_COLOR_MODE =
            new ModeSetting.Builder<NameColorMode>()
                    .name("featurehud.nameColorMode")
                    .defaultValue(NameColorMode.GRADIENT)
                    .option(NameColorMode.FIXED, "fixed")
                    .option(NameColorMode.GRADIENT, "gradient")
                    .option(NameColorMode.RAINBOW, "rainbow")
                    .build();

    static final ModeSetting<GradientDirection> GRADIENT_DIRECTION =
            new ModeSetting.Builder<GradientDirection>()
                    .name("featurehud.gradientDirection")
                    .defaultValue(GradientDirection.HORIZONTAL)
                    .option(GradientDirection.HORIZONTAL, "horizontal")
                    .option(GradientDirection.TOP_TO_BOTTOM, "top_to_bottom")
                    .option(GradientDirection.BOTTOM_TO_TOP, "bottom_to_top")
                    .build();

    static final StringSetting GRADIENT_COLOR =
            new StringSetting.Builder()
                    .name("featurehud.gradientColor")
                    .defaultValue("#765cff")
                    .build();

    static final StringSetting PARAMETER_COLOR =
            new StringSetting.Builder()
                    .name("featurehud.parameterColor")
                    .defaultValue("#ffffff")
                    .build();

    static final DoubleSetting COLOR_SPEED =
            new DoubleSetting.Builder()
                    .name("featurehud.colorSpeed")
                    .defaultValue(0.35D)
                    .range(0.0D, 3.0D)
                    .build();

    static final DoubleSetting COLOR_SPREAD =
            new DoubleSetting.Builder()
                    .name("featurehud.colorSpread")
                    .defaultValue(0.025D)
                    .range(0.0D, 1.0D)
                    .build();

    static final DoubleSetting CHARACTER_COLOR_SPREAD =
            new DoubleSetting.Builder()
                    .name("featurehud.characterColorSpread")
                    .defaultValue(0.004D)
                    .range(0.0D, 0.05D)
                    .build();

    static final IntSetting ALPHA =
            new IntSetting.Builder()
                    .name("featurehud.alpha")
                    .defaultValue(100)
                    .range(0, 100)
                    .build();

    static boolean isVisible() {
        return VISIBLE.get();
    }

    static int setVisible(Minecraft client, boolean value) {
        VISIBLE.set(value);
        return showStatus(client);
    }

    static boolean isTextShadowEnabled() {
        return TEXT_SHADOW.get();
    }

    static int setTextShadowEnabled(boolean value) {
        TEXT_SHADOW.set(value);
        return 1;
    }

    static int setPanelOpacity(int value) {
        PANEL_OPACITY.set(value);
        return 1;
    }

    static int setUseThemeBackground(boolean value) {
        USE_THEME_BACKGROUND.set(value);
        return 1;
    }

    static int setBackgroundColor(String value) {
        if (parseColor(value) == null) return 0;
        BACKGROUND_COLOR.set(normalizeColor(value));
        USE_THEME_BACKGROUND.set(false);
        return 1;
    }

    static double scale() {
        return SCALE.get();
    }

    static int setScale(double value) {
        SCALE.set(value);
        return 1;
    }

    static int setShowTitle(boolean value) {
        SHOW_TITLE.set(value);
        return 1;
    }

    static int setShowFps(boolean value) {
        SHOW_FPS.set(value);
        return 1;
    }

    static int setTitleColor(String value) {
        if (parseColor(value) == null) return 0;
        TITLE_COLOR.set(normalizeColor(value));
        return 1;
    }

    static List<String> fontModeOptions() {
        return FONT_MODE.optionIds();
    }

    static int setFontMode(String value) {
        FONT_MODE.deserialize(value);
        return 1;
    }

    static int setUseThemeColor(boolean value) {
        USE_THEME_COLOR.set(value);
        return 1;
    }

    static int setColor(String value) {
        if (parseColor(value) == null) return 0;
        COLOR.set(normalizeColor(value));
        USE_THEME_COLOR.set(false);
        return 1;
    }

    static List<String> nameColorModeOptions() {
        return NAME_COLOR_MODE.optionIds();
    }

    static int setNameColorMode(String value) {
        NAME_COLOR_MODE.deserialize(value);
        return 1;
    }

    static List<String> gradientDirectionOptions() {
        return GRADIENT_DIRECTION.optionIds();
    }

    static int setGradientDirection(String value) {
        GRADIENT_DIRECTION.deserialize(value);
        return 1;
    }

    static int setGradientColor(String value) {
        if (parseColor(value) == null) return 0;
        GRADIENT_COLOR.set(normalizeColor(value));
        return 1;
    }

    static int setParameterColor(String value) {
        if (parseColor(value) == null) return 0;
        PARAMETER_COLOR.set(normalizeColor(value));
        return 1;
    }

    static int setColorSpeed(double value) {
        COLOR_SPEED.set(value);
        return 1;
    }

    static int setColorSpread(double value) {
        COLOR_SPREAD.set(value);
        return 1;
    }

    static int setCharacterColorSpread(double value) {
        CHARACTER_COLOR_SPREAD.set(value);
        return 1;
    }

    static int setAlpha(int value) {
        ALPHA.set(value);
        return 1;
    }

    static int setPositionX(int value) {
        POSITION_X.set(value);
        return 1;
    }

    static int setPositionY(int value) {
        POSITION_Y.set(value);
        return 1;
    }

    static int showStatus(Minecraft client) {
        ClientChat.send(
                client, "Native feature HUD " + (VISIBLE.get() ? "enabled" : "disabled") + ".");
        return 1;
    }

    static int setTitle(Minecraft client, String value) {
        String next = normalizeTitle(value);
        if (next.isEmpty()) {
            ClientChat.send(client, "HUD title cannot be empty.");
            return 0;
        }
        TITLE.set(next);
        return showTitle(client);
    }

    static int showTitle(Minecraft client) {
        ClientChat.send(client, "HUD title: " + title() + ". Usage: .moons hud title <text>.");
        return 1;
    }

    static String title() {
        String configured = TITLE.get().trim();
        return configured.isEmpty() ? ClientBranding.name() : configured;
    }

    static String titleText() {
        return title();
    }

    static String header(Minecraft client) {
        return SHOW_FPS.get() ? title() + " [" + client.getFps() + "]" : title();
    }

    static String normalizeTitle(String value) {
        String normalized = value == null ? "" : value.replaceAll("[\\p{Cntrl}]", "").trim();
        return normalized.length() <= 32 ? normalized : normalized.substring(0, 32);
    }

    static Integer parseColor(String raw) {
        if (raw == null) return null;
        String value = raw.trim().replace("#", "");
        try {
            if (value.length() == 6 || value.length() == 8) return ColorCodec.parseRgbOrArgb(value);
        } catch (NumberFormatException ignored) {
        }
        return null;
    }

    static String normalizeColor(String raw) {
        String value = raw.trim().replace("#", "");
        return "#" + value.toLowerCase(java.util.Locale.ROOT);
    }
}
