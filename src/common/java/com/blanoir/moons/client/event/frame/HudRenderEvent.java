package com.blanoir.moons.client.event.frame;

import com.blanoir.moons.client.ui.render.LegacyGuiGraphics;

public record HudRenderEvent(LegacyGuiGraphics graphics, float partialTick) {}
