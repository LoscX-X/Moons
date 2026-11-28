package com.blanoir.tempos.client.ui.theme;

/** PanelLook's palette and the shared interaction timings, in GUI pixels and milliseconds. */
public final class Theme {
    public static final int BRAND = 0xffdcdce2;
    public static final int PANEL = 0xff1b1b1e, HEADER = 0xff19191c, ROW = 0xff232326;
    public static final int FIELD = 0xff161619, HOVER = 0xff2c2c31, SELECTED = 0xff303035;
    public static final int BORDER = 0xff39393e, TRACK = 0xff45454d;
    public static final int TEXT = 0xffe8e8ec, MUTED = 0xffa5a5ae, DIM = 0xff888891;
    public static final int SHADOW = 0x68000000, SELECTION = 0x664d89c4;
    public static final int DANGER = 0xffd8776d;
    public static final int HOVER_MS = 150, ENABLE_MS = 225, HANDLE_MS = 50, EXPAND_MS = 180;
    public static final int TOOLTIP_MS = 600;
    public static final float WINDOW_RADIUS = 4, ROW_RADIUS = 3, GAP = 4, PAD = 6;
    public static final float LINE = 20, FONT_SIZE = 12;

    /** Floating-window measurements are in logical GUI pixels. */
    public static final class Floating {
        private Floating() {}
        public static final float WIDTH = 110, GAP = 6, HEADER = 17, ROW = 18, GROUP = 11, FOOTER = 18;
        // Inter line-box sizes, in GUI pixels. Keep the reference hierarchy at the smaller requested scale.
        public static final float BRAND_SIZE = 7.3f, TEXT_SIZE = 6.65f, FIELD_SIZE = 6.15f, DETAIL_SIZE = 5.7f;
        public static final float RADIUS = 1.5f, SHADOW_BLUR = 2, BOTTOM = 2, MAX_HEIGHT = 300;
        public static final float ICON = 7, ICON_X = 7, TEXT_X = 19, ARROW_RIGHT = 7, MARGIN = 20, TOP = 22;
        public static final int WINDOW = 0xff1a191a, TITLE_BAR = 0xff141414, HOVER = 0xff1f1e1f;
        public static final int BORDER = 0xff1f2128, SHADOW = 0x59000000;
        public static final int BRAND = 0xffffffff, GEAR = 0xff797979, ICON_COLOR = 0xffa2a2a2;
        public static final int LABEL = 0xff8e8e8e, ARROW = 0xff4e4e4e;
        public static final int MODULE = 0xffc4c4c4, LABEL_HI = 0xffbdbdbd, GROUP_TITLE = 0xff5c5c5c;
        public static final int SETTINGS = 0xff161516, KEY = 0x11ffffff, RULE = 0xff242324;
    }

    private static final long ORIGIN_NANOS = System.nanoTime();
    private static final float FIXED_BASE = fixedBase();
    private final AccentClock clock = new AccentClock();
    private int accent = BRAND;
    private boolean rgb;
    private long revision;

    private static float fixedBase() {
        String value = System.getProperty("tempos.ui.accentBase");
        if (value == null) return Float.NaN;
        try {
            double phase = Double.parseDouble(value);
            return Double.isFinite(phase) ? (float)(phase - Math.floor(phase)) : Float.NaN;
        } catch (NumberFormatException ignored) {
            return Float.NaN;
        }
    }
    public AccentClock clock() { return clock; }
    public boolean rgb() { return rgb; }
    public void rgb(boolean enabled) {
        if (rgb != enabled) { rgb = enabled; revision++; }
    }
    public long revision() { return revision + clock.layoutRevision(); }
    public void frame(long now) {
        if (!rgb) return;
        if (Float.isNaN(FIXED_BASE)) clock.sample((now - ORIGIN_NANOS) / 1_000_000_000.0);
        else clock.freeze(FIXED_BASE);
    }
    public int accentTint(float y, int alpha, float multiplier) {
        if (rgb) return clock.pack(y, alpha, multiplier);
        int r = Math.round((accent >>> 16 & 255) * multiplier);
        int g = Math.round((accent >>> 8 & 255) * multiplier);
        int b = Math.round((accent & 255) * multiplier);
        return Math.clamp(alpha, 0, 255) << 24 | r << 16 | g << 8 | b;
    }
    public int textOnAccent() {
        int light = 299 * (accent >>> 16 & 255) + 587 * (accent >>> 8 & 255) + 114 * (accent & 255);
        return rgb || light > 130000 ? 0xff2d2d2d : TEXT;
    }

    public int accent() { return accent; }
    public void accent(int argb) {
        if (accent != argb) { accent = argb; revision++; }
    }
}
