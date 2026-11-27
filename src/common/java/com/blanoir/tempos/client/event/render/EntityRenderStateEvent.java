package com.blanoir.tempos.client.event.render;

import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;

/** Render-state extraction result after built-in Tempos adjustments. */
public record EntityRenderStateEvent(Entity entity, EntityRenderState state, float partialTick) {}
