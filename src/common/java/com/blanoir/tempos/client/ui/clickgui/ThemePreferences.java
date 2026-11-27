package com.blanoir.tempos.client.ui.clickgui;

import com.blanoir.tempos.client.config.Settings;
import com.blanoir.tempos.client.ui.theme.Theme;
import com.blanoir.tempos.client.ui.widget.ColorPicker;
import com.blanoir.tempos.client.utils.render.ColorCodec;
import java.util.List;

/** Adapts appearance settings to the renderer. Drag previews are written once per completed gesture. */
public final class ThemePreferences implements ColorPicker.Binding {
    public static final String BRAND_KEY = "clickgui.theme.color";
    public static final String HUD_RGB_KEY = "clickgui.theme.rgb.hud";
    public static boolean hudRgb() { return Settings.getBoolean(HUD_RGB_KEY,false); }
    public static void toggleHudRgb() { Settings.setBoolean(HUD_RGB_KEY,!hudRgb()); }
    public static final String DEFAULT_BRAND = "#dcdce2";
    public static final List<String> SETTINGS_PRESETS = List.of(DEFAULT_BRAND, "#a4a6a9", "#5d9cec", "#ae8de6");
    public static final List<String> PANEL_PRESETS = List.of(DEFAULT_BRAND, "#ff4050", "#c84cff", "#6c7cff",
            "#27b8ff", "#22c98b", "#a4a6a9", "#f06aa6");
    public enum Parameter {
        SPEED("Speed", "speed", .1, 10, .1, 1),
        SATURATION("Saturation", "saturation", 0, 1, .01, .78),
        BRIGHTNESS("Brightness", "brightness", 0, 1, .01, 1),
        SPREAD("Color spread", "spread", 0, .005, .0001, .001);

        public final String label, key;
        public final double min, max, step, initial;
        Parameter(String label, String name, double min, double max, double step, double initial) {
            this.label = label; this.key = "clickgui.theme.rgb." + name;
            this.min = min; this.max = max; this.step = step; this.initial = initial;
        }
    }
    private static final Parameter[] PARAMETERS = Parameter.values();
    private final double[] values = new double[PARAMETERS.length];
    private final Theme theme;
    private int dirty;

    public ThemePreferences(Theme theme) {
        this.theme = theme;
        reload();
    }
    public void reload() {
        dirty = 0;
        for (Parameter p : PARAMETERS) values[p.ordinal()] = Math.clamp(Settings.getDouble(p.key, p.initial), p.min, p.max);
        updateClock();
        theme.rgb("rgb".equals(Settings.getString("clickgui.theme", "brand")));
        try {
            String value = brandValue();
            theme.accent(ColorCodec.parseRgbOrArgb(value.startsWith("#") ? value.substring(1) : value));
        } catch (NumberFormatException ignored) {
            theme.accent(Theme.BRAND);
        }
    }
    public static String brandValue() {
        String value = Settings.getString(BRAND_KEY, DEFAULT_BRAND);
        return "#b29a65".equalsIgnoreCase(value) || "#f0b43c".equalsIgnoreCase(value) ? DEFAULT_BRAND : value;
    }
    @Override public String hex() { return brandValue(); }
    @Override public void preview(int argb) { theme.accent(argb); }
    @Override public void commit(String value) { Settings.setString(BRAND_KEY, value); }
    @Override public boolean rgb() { return theme.rgb(); }
    @Override public void rgb(boolean enabled) {
        if (enabled == theme.rgb()) return;
        theme.rgb(enabled);
        Settings.setString("clickgui.theme", enabled ? "rgb" : "brand");
    }
    public double value(Parameter p) { return values[p.ordinal()]; }
    public void preview(Parameter p, double value) {
        if (!Double.isFinite(value)) return;
        double clamped = Math.clamp(value, p.min, p.max);
        if (values[p.ordinal()] == clamped) return;
        values[p.ordinal()] = clamped;
        dirty |= 1 << p.ordinal();
        updateClock();
    }
    private void updateClock() {
        theme.clock().configure(value(Parameter.SPEED), value(Parameter.SATURATION),
                value(Parameter.BRIGHTNESS), value(Parameter.SPREAD));
    }
    public void saveNumbers() {
        if (dirty == 0) return;
        try (var batch = Settings.batch()) {
            for (Parameter p : PARAMETERS) if ((dirty & 1 << p.ordinal()) != 0) Settings.setDouble(p.key, value(p));
            dirty = 0;
        }
    }
}
