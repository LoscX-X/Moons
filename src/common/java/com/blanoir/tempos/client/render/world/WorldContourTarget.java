package com.blanoir.tempos.client.render.world;

import com.blanoir.tempos.client.access.MinecraftClientAccess;
import com.blanoir.tempos.client.render.VisualLayerBlit;
import com.blanoir.tempos.client.render.VisualRenderTargets;
import com.blanoir.tempos.client.render.model.VisualModelOutline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import java.lang.ref.WeakReference;
import java.util.function.BiFunction;

/** A depth-free mask: overlapping faces form one silhouette before edges are extracted. */
public final class WorldContourTarget implements AutoCloseable {
    private final BiFunction<Integer, Integer, RenderTarget> allocate;
    private RenderTarget mask;
    private WeakReference<Object> level = new WeakReference<>(null);
    private Object device;

    /** Target construction differs between game versions; ownership and composition do not. */
    public WorldContourTarget(BiFunction<Integer, Integer, RenderTarget> allocate) {
        this.allocate = allocate;
    }

    /** The caller clears this mask and writes opaque colored faces, never wireframe edges. */
    public RenderTarget begin(Minecraft client) {
        if (client == null || client.level == null || client.player == null) return null;
        var scene = MinecraftClientAccess.mainRenderTarget(client);
        if (scene.width <= 0 || scene.height <= 0) return null;
        Object currentDevice = RenderSystem.getDevice();
        if (mask != null && (level.get() != client.level || device != currentDevice)) close();
        if (mask == null) {
            mask = allocate.apply(scene.width, scene.height);
            level = new WeakReference<>(client.level);
            device = currentDevice;
        } else if (mask.width != scene.width || mask.height != scene.height) {
            mask.resize(scene.width, scene.height);
        }
        return mask;
    }

    /** Only the extracted outline enters the visual layer; the opaque mask is never presented. */
    public boolean composite(Minecraft client) {
        if (mask == null || !VisualModelOutline.apply(mask)) return false;
        var destination = VisualRenderTargets.worldTarget(client);
        if (destination == null) return false;
        VisualLayerBlit.drawStraight(mask.getColorTextureView(), destination);
        return true;
    }

    @Override
    public void close() {
        if (mask != null) mask.destroyBuffers();
        mask = null;
        level.clear();
        device = null;
    }
}
