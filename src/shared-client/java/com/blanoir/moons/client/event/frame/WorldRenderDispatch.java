package com.blanoir.moons.client.event.frame;

import com.blanoir.moons.client.event.EventBus;
import com.mojang.blaze3d.vertex.PoseStack;

/**
 * Emits the custom world overlay once per game-renderer frame.
 *
 * <p>Sodium renders its terrain through a redirected chunk-layer call. A host
 * can consequently remove the vanilla instruction used by the regular
 * world-render hook. Both boundaries feed this dispatcher, so whichever one
 * survives in the active renderer supplies the event without drawing twice.</p>
 */
public final class WorldRenderDispatch {
    private static long frame;
    private static long renderedFrame = -1L;

    private WorldRenderDispatch() {
    }

    public static void beginFrame() {
        frame++;
    }

    public static void post(PoseStack poseStack, float tickDelta) {
        if (poseStack == null || renderedFrame == frame) {
            return;
        }

        renderedFrame = frame;
        EventBus.WORLD_RENDER.post(new WorldRenderEvent(poseStack, tickDelta));
    }
}
