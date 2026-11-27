package com.blanoir.tempos.client.event.frame;

import net.minecraft.client.Minecraft;

public record FrameEvent(Minecraft client, double deltaSeconds) {}
