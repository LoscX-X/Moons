package com.blanoir.moons.client.ui.component;

import com.blanoir.moons.client.ui.animation.UiMotion;
import com.blanoir.moons.client.ui.render.SmoothGui;
import com.blanoir.moons.client.ui.theme.UiPalette;
import com.blanoir.moons.client.ui.theme.UiTheme;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Shared visual implementation for rows, toggles and slider tracks. */
public final class ControlRenderer {
    private final UiPalette colors;

    public ControlRenderer(UiTheme theme) {
        colors = theme.palette();
    }

    public int rowColor(UiMotion.Visual visual) {
        int color = UiMotion.mixColor(colors.row(), colors.rowHover(), visual.hover());
        return UiMotion.mixColor(color, colors.selected(), visual.selected());
    }

    public void toggle(GuiGraphicsExtractor graphics, int x, int y, int width, int height,
                       int radius, UiMotion.Visual visual) {
        double progress = visual.selected();
        int track = UiMotion.mixColor(colors.controlTrack(), colors.rowHover(), visual.hover() * 0.55D);
        track = UiMotion.mixColor(track, colors.primaryDark(), progress);
        SmoothGui.roundedRect(graphics, x, y, x + width, y + height, radius, track);
        int knobWidth = Math.max(5, height - 4);
        int knobTravel = Math.max(0, width - knobWidth - 4);
        graphics.pose().pushMatrix();
        graphics.pose().translate((float) (knobTravel * progress), 0.0F);
        int knob = x + 2;
        SmoothGui.roundedRect(graphics, knob, y + 2, knob + knobWidth, y + height - 2,
                Math.max(2, radius - 2),
                progress > 0.5D ? colors.textPrimary() : colors.textSecondary());
        graphics.pose().popMatrix();
    }

    public void slider(GuiGraphicsExtractor graphics, int left, int right, int y,
                       int low, int high, boolean range) {
        SmoothGui.roundedRect(graphics, left, y, right, y + 2, 1, colors.controlTrack());
        SmoothGui.roundedRect(graphics, low, y, high, y + 2, 1, colors.primaryDark());
        drawHandle(graphics, high, y);
        if (range) drawHandle(graphics, low, y);
    }

    private void drawHandle(GuiGraphicsExtractor graphics, int x, int y) {
        SmoothGui.roundedRect(graphics, x - 2, y - 2, x + 2, y + 4, 2, colors.primary());
    }
}
