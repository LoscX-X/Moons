package com.blanoir.moons.client.event.frame;

import com.blanoir.moons.client.render.LegacyPoseStack;

/** World-render boundary emitted after translucent terrain. */
public record WorldRenderEvent(LegacyPoseStack poseStack, float tickDelta) {}
