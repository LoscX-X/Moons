package com.blanoir.moons.client.event.frame;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphics;

public record HudRenderEvent(GuiGraphics graphics, DeltaTracker deltaTracker) {}
