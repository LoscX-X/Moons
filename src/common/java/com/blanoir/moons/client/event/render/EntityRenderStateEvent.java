package com.blanoir.moons.client.event.render;

import com.blanoir.moons.client.compat.render.EntityRenderState;

import net.minecraft.entity.Entity;

/** Render-state extraction result after built-in Moons adjustments. */
public record EntityRenderStateEvent(Entity entity, EntityRenderState state, float partialTick) {}
