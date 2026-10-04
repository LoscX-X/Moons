package com.blanoir.moons.client.event.render;

import net.minecraft.entity.EntityLivingBase;

/** Living-entity submit boundaries on the render thread. */
public final class LivingRenderEvent {
    private LivingRenderEvent() {}

    public record Pre(EntityLivingBase state) {}

    public record Post(EntityLivingBase state) {}
}
