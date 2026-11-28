package com.blanoir.tempos.client.event.frame;

import com.blanoir.tempos.client.event.EventBus;
import com.blanoir.tempos.client.render.VisualRenderTargets;
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
    private static Object pendingPass;
    private static PoseStack pendingStack;
    private static float pendingDelta;

    private WorldRenderDispatch() {}

    public static void beginFrame() {
        com.blanoir.tempos.client.render.VisualModelCapture.beginFrame();
        frame++;
        clearPending();
        VisualRenderTargets.beginFrame();
    }

    public static void post(PoseStack poseStack, float tickDelta) {
        if (poseStack == null || renderedFrame == frame) {
            return;
        }

        renderedFrame = frame;
        clearPending();
        EventBus.WORLD_RENDER.post(new WorldRenderEvent(poseStack, tickDelta));
    }

    /** RenderPearl terrain callers own the pass beyond Sodium's method return. */
    public static void defer(Object pass, PoseStack poseStack, float tickDelta) {
        if (pass == null || poseStack == null || renderedFrame == frame) return;
        pendingPass = pass;
        pendingStack = poseStack;
        pendingDelta = tickDelta;
    }

    public static void afterPass(Object pass) {
        if (pass == null || pass != pendingPass) return;
        PoseStack matrices = pendingStack;
        float delta = pendingDelta;
        clearPending();
        post(matrices, delta);
    }

    private static void clearPending() {
        pendingPass = null;
        pendingStack = null;
        pendingDelta = 0;
    }
}
