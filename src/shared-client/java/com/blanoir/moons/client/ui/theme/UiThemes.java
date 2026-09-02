package com.blanoir.moons.client.ui.theme;

/** Built-in themes. Custom colors remain module settings layered over this default. */
public final class UiThemes {
    /** Warm white surfaces with low-saturation, slightly greyed gold accents. */
    public static final UiTheme PEARL_GOLD = new UiTheme(
            new UiPalette(
                    0x72FFFDF8,
                    0xD9FFFEFA,
                    0xCAF4F0E7,
                    0xD9FFFFFF,
                    0xF2FFFDF8,
                    0xF0E7DECA,
                    0xFFB6AB93,
                    0xFFA18D62,
                    0xFFC8B991,
                    0xFF7D7158,
                    0xFF302D28,
                    0xFF736D63,
                    0xFF9A9489,
                    0xFFD8D2C5,
                    0xE8FBF9F3
            ),
            new UiMetrics(
                    0.78F,
                    9,
                    5,
                    24,
                    3,
                    8,
                    7,
                    25,
                    11,
                    13.0D,
                    14.0D
            )
    );

    public static final UiTheme COFFEE = new UiTheme(
            new UiPalette(
                    0xF027211E,
                    0xF0342C27,
                    0xD02D2723,
                    0xE03A322D,
                    0xF0473D36,
                    0xF052443A,
                    0xFF67584D,
                    0xFFC49A6C,
                    0xFFE0C7A8,
                    0xFF8B6547,
                    0xFFF4E8D8,
                    0xFFC9B7A3,
                    0xFF8C7D70,
                    0xFF5C5047,
                    0xFF251F1C
            ),
            new UiMetrics(
                    0.78F,
                    9,
                    5,
                    24,
                    3,
                    8,
                    7,
                    25,
                    11,
                    13.0D,
                    14.0D
            )
    );

    private UiThemes() {
    }
}
