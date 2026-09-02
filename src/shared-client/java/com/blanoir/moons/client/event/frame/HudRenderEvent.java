package com.blanoir.moons.client.event.frame;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;

public record HudRenderEvent(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) { }
