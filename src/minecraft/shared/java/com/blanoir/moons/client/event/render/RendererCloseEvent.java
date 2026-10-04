package com.blanoir.moons.client.event.render;

import net.minecraft.client.renderer.EntityRenderer;

/** EntityRenderer close completion boundary on the render thread. */
public record RendererCloseEvent(EntityRenderer renderer) {}
