package com.blanoir.moons.client.event.render;

import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;

/** Living-entity submit boundaries on the render thread. */
public final class LivingRenderEvent {
    private LivingRenderEvent() {}

    public record Pre(LivingEntityRenderState state) {}

    public record Post(LivingEntityRenderState state) {}
}
