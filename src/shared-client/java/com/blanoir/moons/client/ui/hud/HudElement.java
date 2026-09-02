package com.blanoir.moons.client.ui.hud;

import com.blanoir.moons.client.ui.layout.Bounds;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Renderable HUD unit with persisted visual state and editor-visible bounds. */
public interface HudElement {
    String id();

    Bounds bounds();

    void render(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, boolean editing);
}
