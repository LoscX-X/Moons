package com.blanoir.moons.client.event.frame;

import com.blanoir.moons.client.event.EventBus;
import com.blanoir.moons.client.render.LegacyPoseStack;
import com.blanoir.moons.client.render.VisualRenderTargets;

/** Emits the custom world overlay once per renderer frame, before the hand projection. */
public final class WorldRenderDispatch {
    private static long frame;
    private static long renderedFrame = -1L;

    private WorldRenderDispatch() {}

    public static void beginFrame() {
        com.blanoir.moons.client.render.VisualModelCapture.beginFrame();
        frame++;
        VisualRenderTargets.beginFrame();
    }

    public static void post(LegacyPoseStack poseStack, float tickDelta) {
        if (poseStack == null || renderedFrame == frame) {
            return;
        }

        renderedFrame = frame;
        EventBus.WORLD_RENDER.post(new WorldRenderEvent(poseStack, tickDelta));
    }
}
