package com.blanoir.tempos.client.ui.paint;

import com.blanoir.tempos.client.ui.theme.AccentClock;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** The renderer owns these sources so a late-opened screen need not reload game resources. */
public final class UiShaders {
    public static final int IDENTITY_BYTES = 160;
    private final String vertex;
    private final String fragment;
    private final String glyph;
    private final ByteBuffer accent = ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder());

    public UiShaders(boolean explicitLocations) {
        String header = explicitLocations
                ? "#version 330\n#extension GL_ARB_separate_shader_objects : require\n#define UI_LOCATION(n) layout(location = n)\n#define UI_TEXTURE_MATRIX_FIRST\n"
                : "#version 330\n#define UI_LOCATION(n)\n";
        vertex = header + read("shape.vsh");
        fragment = header + read("shape.fsh");
        glyph = header + read("glyph.fsh");
    }

    private static String read(String filename) {
        String path = "/assets/tempos/shaders/ui/" + filename;
        try (var input = UiShaders.class.getResourceAsStream(path)) {
            if (input == null) throw new IllegalStateException("Missing UI shader " + path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new UncheckedIOException("Cannot read " + path, failure);
        }
    }

    public String vertex() { return vertex; }
    public String fragment() { return fragment; }
    public String glyph() { return glyph; }
    public static int accentOffset(boolean textureMatrixFirst) { return textureMatrixFirst ? 128 : 64; }
    public ByteBuffer accentUniform() { return accent.position(0); }
    public boolean updateAccent(AccentClock clock) {
        if (clock == null) return false;
        float panel = (float)AccentClock.PANEL_LUMINANCE;
        if (accent.getFloat(0) == clock.base() && accent.getFloat(4) == clock.saturation()
                && accent.getFloat(8) == clock.brightness() && accent.getFloat(12) == panel) return false;
        accent.putFloat(0, clock.base()).putFloat(4, clock.saturation())
                .putFloat(8, clock.brightness()).putFloat(12, panel);
        return true;
    }

    /** A zeroed std140 block can serve both Projection and DynamicTransforms for clip-space UI. */
    public static void writeIdentityTransforms(ByteBuffer block, boolean textureMatrixFirst) {
        int texture = textureMatrixFirst ? 64 : 96;
        int color = accentOffset(textureMatrixFirst);
        for (int i = 0; i < 4; i++) {
            block.putFloat(i * 20, 1);
            block.putFloat(texture + i * 20, 1);
            block.putFloat(color + i * 4, 1);
        }
    }
}
