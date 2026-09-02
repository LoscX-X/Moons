package com.blanoir.moons.client.event.frame;

import com.mojang.blaze3d.vertex.PoseStack;

/** World-render boundary emitted after translucent terrain. */
public record WorldRenderEvent(PoseStack poseStack, float tickDelta) { }
