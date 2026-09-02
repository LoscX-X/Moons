package com.blanoir.moons.client.event.render;

import net.minecraft.client.renderer.GameRenderer;

/** GameRenderer close completion boundary on the render thread. */
public record RendererCloseEvent(GameRenderer renderer) { }
