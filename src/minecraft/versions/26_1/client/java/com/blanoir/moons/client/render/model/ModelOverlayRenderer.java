package com.blanoir.moons.client.render.model;

import com.blanoir.moons.client.config.ClientBranding;
import com.blanoir.moons.client.render.VisualModelCapture;
import com.blanoir.moons.client.render.VisualRenderTargets;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.MeshData;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.OutputTarget;
import net.minecraft.client.renderer.rendertype.RenderType;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Version-owned model capture shared by player highlighting and display-only extra models. */
public final class ModelOverlayRenderer {
    private static boolean normalEnabled;

    private static final ModelOverlayTarget RENDER_TARGET =
            new ModelOverlayTarget(ClientBranding.name() + " Model visuals");
    private static final Set<Object> HELD_ITEM_SUBMITS =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private static final Map<Object, Integer> GHOST_ITEM_SUBMITS = new IdentityHashMap<>();
    private static final Map<Integer, ModelOverlayTarget> OVERLAY_TARGETS = new LinkedHashMap<>();
    private static final Set<OutputTarget> USED_OVERLAYS =
            Collections.newSetFromMap(new IdentityHashMap<>());

    private static boolean activePlayer;
    private static boolean dirty;
    private static Object renderingItemSubmit;

    private ModelOverlayRenderer() {}

    public static void setNormalEnabled(boolean value) {
        normalEnabled = value;
        if (!value) activePlayer = false;
    }

    public static OutputTarget captureOutputTarget() {
        return RENDER_TARGET.outputTarget();
    }

    public static void beginPlayer(boolean eligible) {
        activePlayer = normalEnabled && eligible;
    }

    public static void endPlayer() {
        activePlayer = false;
    }

    /**
     * Remaps body/equipment/cape render types submitted while a shown player is being
     * submitted.
     */
    public static RenderType remapIfNeeded(RenderType renderType) {
        if (VisualModelCapture.active()) return remapOverlayModel(renderType);
        if (!activePlayer || !ModelOverlayMaterials.supports(renderType)) {
            return renderType;
        }

        return ModelOverlayMaterials.remap(renderType, captureOutputTarget());
    }

    public static RenderType remapOverlayModel(RenderType renderType) {
        return remapOverlayModel(renderType, VisualModelCapture.outlineColor());
    }

    private static RenderType remapOverlayModel(RenderType renderType, int outlineColor) {
        ModelOverlayTarget target =
                OVERLAY_TARGETS.computeIfAbsent(
                        outlineColor,
                        color ->
                                new ModelOverlayTarget(
                                        ClientBranding.name()
                                                + " Additional models "
                                                + Integer.toHexString(color)));
        return ModelOverlayMaterials.remap(renderType, target.outputTarget(), true);
    }

    public static void markHeldItemSubmit(Object submit) {
        if (VisualModelCapture.active() && submit != null) {
            GHOST_ITEM_SUBMITS.put(submit, VisualModelCapture.outlineColor());
        }
        if (activePlayer && submit != null) {
            HELD_ITEM_SUBMITS.add(submit);
        }
    }

    public static void beginRenderingItemSubmit(Object submit) {
        renderingItemSubmit = submit;
    }

    public static void endRenderingItemSubmit() {
        renderingItemSubmit = null;
    }

    /**
     * Remaps a held-item render type at draw time when the item was submitted by a
     * chams-eligible player.
     */
    public static RenderType remapHeldItemRenderType(RenderType renderType) {
        if (renderingItemSubmit != null && GHOST_ITEM_SUBMITS.containsKey(renderingItemSubmit)) {
            return remapOverlayModel(renderType, GHOST_ITEM_SUBMITS.get(renderingItemSubmit));
        }
        if (!normalEnabled
                || renderingItemSubmit == null
                || !HELD_ITEM_SUBMITS.contains(renderingItemSubmit)
                || !ModelOverlayMaterials.supports(renderType)) {
            return renderType;
        }

        return ModelOverlayMaterials.remap(renderType, captureOutputTarget());
    }

    public static void beginFrame() {
        dirty = false;
        RENDER_TARGET.beginFrame();
        USED_OVERLAYS.clear();
        OVERLAY_TARGETS.values().forEach(ModelOverlayTarget::beginFrame);
    }

    public static void composite() {
        try {
            if (!normalEnabled) RENDER_TARGET.close();
            if (!dirty && USED_OVERLAYS.isEmpty()) {
                return;
            }

            var destination = VisualRenderTargets.worldTarget(Minecraft.getInstance());
            if (dirty) RENDER_TARGET.composite(destination);
            for (var entry : OVERLAY_TARGETS.entrySet()) {
                if (USED_OVERLAYS.contains(entry.getValue().outputTarget()))
                    entry.getValue().composite(destination, entry.getKey());
            }
        } finally {
            dirty = false;
            HELD_ITEM_SUBMITS.clear();
            GHOST_ITEM_SUBMITS.clear();
            OVERLAY_TARGETS
                    .entrySet()
                    .removeIf(
                            entry -> {
                                var target = entry.getValue();
                                if (USED_OVERLAYS.contains(target.outputTarget())) return false;
                                ModelOverlayMaterials.forgetTarget(target.outputTarget());
                                target.close();
                                return true;
                            });
            USED_OVERLAYS.clear();
            if (OVERLAY_TARGETS.keySet().stream().noneMatch(color -> (color >>> 24) != 0))
                VisualModelOutline.close();
        }
    }

    /** Draws a synchronous borrowed view before vanilla consumes and closes the source mesh. */
    public static boolean captureDraw(RenderType type, MeshData mesh) {
        var capture = ModelOverlayMaterials.capture(type.outputTarget());
        if (capture == null || (!capture.exclusive() && !normalEnabled)) return false;
        var colorOverride = RenderSystem.outputColorTextureOverride;
        var depthOverride = RenderSystem.outputDepthTextureOverride;
        var modelView = RenderSystem.getModelViewStack();
        boolean layered = false;
        try {
            RenderSystem.outputColorTextureOverride = null;
            RenderSystem.outputDepthTextureOverride = null;
            if (capture.layeringModifier() != null) {
                modelView.pushMatrix();
                layered = true;
                capture.layeringModifier().accept(modelView);
            }
            capture.layer().draw(new BorrowedMesh(mesh));
            if (capture.exclusive()) USED_OVERLAYS.add(capture.layer().outputTarget());
            else dirty = true;
        } catch (RuntimeException failure) {
            // In particular, a failed extra model must never fall through to the game target.
            System.getLogger(ModelOverlayRenderer.class.getName())
                    .log(System.Logger.Level.WARNING, "Skipping captured model", failure);
        } finally {
            if (layered) modelView.popMatrix();
            RenderSystem.outputColorTextureOverride = colorOverride;
            RenderSystem.outputDepthTextureOverride = depthOverride;
            if (capture.exclusive()) mesh.close();
        }
        return capture.exclusive();
    }

    private static final class BorrowedMesh extends MeshData {
        private final MeshData source;

        BorrowedMesh(MeshData source) {
            super(null, source.drawState());
            this.source = source;
        }

        @Override
        public ByteBuffer vertexBuffer() {
            return source.vertexBuffer();
        }

        @Override
        public ByteBuffer indexBuffer() {
            return source.indexBuffer();
        }

        @Override
        public void close() {
            // The original draw owns both buffers, including any pre-sorted index buffer.
        }
    }

    public static void close() {
        activePlayer = false;
        renderingItemSubmit = null;
        dirty = false;
        HELD_ITEM_SUBMITS.clear();
        GHOST_ITEM_SUBMITS.clear();
        ModelOverlayMaterials.clear();
        RENDER_TARGET.close();
        OVERLAY_TARGETS.values().forEach(ModelOverlayTarget::close);
        OVERLAY_TARGETS.clear();
        USED_OVERLAYS.clear();
        VisualModelOutline.close();
    }
}
