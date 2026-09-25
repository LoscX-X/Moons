package com.blanoir.moons.client.render.model;

import com.blanoir.moons.client.config.ClientBranding;
import com.blanoir.moons.client.render.VisualModelCapture;
import com.blanoir.moons.client.render.VisualRenderTargets;
import com.mojang.blaze3d.systems.RenderSystem;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import net.minecraft.client.renderer.rendertype.RenderType;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;

/** Version-owned model capture shared by player highlighting and display-only extra models. */
public final class ModelOverlayRenderer {
    private static boolean normalEnabled;
    private static final ModelOverlayTarget NORMAL_TARGET =
            new ModelOverlayTarget(ClientBranding.name() + " Player visuals");
    private static final Map<Integer, ModelOverlayTarget> OVERLAY_TARGETS = new LinkedHashMap<>();
    private static final Set<Object> HELD_ITEM_SUBMITS =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private static final Map<Object, Integer> OVERLAY_ITEM_SUBMITS = new IdentityHashMap<>();
    private static boolean activePlayer;
    private static boolean dirty;
    private static Object renderingItemSubmit;

    private ModelOverlayRenderer() {}

    public static void setNormalEnabled(boolean value) {
        normalEnabled = value;
        if (!value) activePlayer = false;
    }

    private record CapturedDraw(
            PreparedRenderType type, StagedVertexBuffer.ExecuteInfo info, Integer outlineColor) {}

    private static final Set<CapturedDraw> draws = new LinkedHashSet<>();
    private static boolean replaying;

    public static boolean captureDraw(Object type, Object info) {
        if (replaying
                || !dirty
                || !(type instanceof PreparedRenderType prepared)
                || !(info instanceof StagedVertexBuffer.ExecuteInfo execute)) return false;
        Integer outline = ModelOverlayMaterials.outlineColor(prepared.name());
        boolean exclusive = outline != null;
        if (!exclusive && (!normalEnabled || !prepared.name().startsWith("moons_chams_")))
            return false;
        // OIT visits the same draw in several passes; replay its original pipeline once per style.
        draws.add(new CapturedDraw(prepared, execute, outline));
        // The normal player still draws into the scene. Only additional models are consumed.
        return exclusive;
    }

    public static void beginPlayer(boolean eligible) {
        activePlayer = normalEnabled && eligible;
    }

    public static void endPlayer() {
        activePlayer = false;
    }

    /** Marks body/equipment/cape draws while an eligible player is submitted. */
    public static RenderType remapIfNeeded(RenderType renderType) {
        if (VisualModelCapture.active()) return remapOverlayModel(renderType);
        if (!activePlayer || !ModelOverlayMaterials.supports(renderType)) return renderType;
        dirty = true;
        return ModelOverlayMaterials.remap(renderType);
    }

    public static RenderType remapOverlayModel(RenderType renderType) {
        return remapOverlayModel(renderType, VisualModelCapture.outlineColor());
    }

    private static RenderType remapOverlayModel(RenderType renderType, int outlineColor) {
        dirty = true;
        return ModelOverlayMaterials.overlayModel(renderType, outlineColor);
    }

    public static void markHeldItemSubmit(Object submit) {
        if (submit == null) return;
        if (VisualModelCapture.active()) {
            // Item draws happen after their collector scope has ended; retain its style explicitly.
            OVERLAY_ITEM_SUBMITS.put(submit, VisualModelCapture.outlineColor());
        } else if (activePlayer) {
            HELD_ITEM_SUBMITS.add(submit);
        }
    }

    public static void beginRenderingItemSubmit(Object submit) {
        renderingItemSubmit = submit;
    }

    public static void endRenderingItemSubmit() {
        renderingItemSubmit = null;
    }

    public static RenderType remapHeldItemRenderType(RenderType renderType) {
        Integer outline = OVERLAY_ITEM_SUBMITS.get(renderingItemSubmit);
        if (outline != null) return remapOverlayModel(renderType, outline);
        if (!normalEnabled
                || renderingItemSubmit == null
                || !HELD_ITEM_SUBMITS.contains(renderingItemSubmit)
                || !ModelOverlayMaterials.supports(renderType)) return renderType;
        dirty = true;
        return ModelOverlayMaterials.remap(renderType);
    }

    public static void beginFrame() {
        // Material marking and item extraction may precede this draw-phase boundary.
        draws.clear();
    }

    public static void composite() {
        Set<Integer> usedStyles = new LinkedHashSet<>();
        try {
            if (!dirty || draws.isEmpty()) {
                if (!normalEnabled) NORMAL_TARGET.close();
                return;
            }
            Set<CapturedDraw> normal = new LinkedHashSet<>();
            Map<Integer, Set<CapturedDraw>> overlays = new LinkedHashMap<>();
            for (CapturedDraw draw : draws) {
                if (draw.outlineColor() == null) {
                    if (normalEnabled) normal.add(draw);
                } else {
                    overlays.computeIfAbsent(draw.outlineColor(), ignored -> new LinkedHashSet<>())
                            .add(draw);
                }
            }
            usedStyles.addAll(overlays.keySet());
            var world = VisualRenderTargets.worldTarget(Minecraft.getInstance());
            if (normal.isEmpty()) {
                if (!normalEnabled) NORMAL_TARGET.close();
            } else {
                replay(NORMAL_TARGET, normal);
                NORMAL_TARGET.composite(world);
            }
            for (var entry : overlays.entrySet()) {
                int color = entry.getKey();
                ModelOverlayTarget target =
                        OVERLAY_TARGETS.computeIfAbsent(
                                color,
                                value ->
                                        new ModelOverlayTarget(
                                                ClientBranding.name()
                                                        + " Model outline "
                                                        + Integer.toUnsignedString(value, 16)));
                replay(target, entry.getValue());
                target.composite(world, color);
            }
        } finally {
            dirty = false;
            draws.clear();
            HELD_ITEM_SUBMITS.clear();
            OVERLAY_ITEM_SUBMITS.clear();
            // Keep active styles across frames, but color changes cannot accumulate full-screen
            // targets.
            var iterator = OVERLAY_TARGETS.entrySet().iterator();
            while (iterator.hasNext()) {
                var entry = iterator.next();
                if (!usedStyles.contains(entry.getKey())) {
                    entry.getValue().close();
                    iterator.remove();
                }
            }
            ModelOverlayMaterials.retainOverlayStyles(usedStyles);
            if (usedStyles.stream().noneMatch(color -> (color >>> 24) != 0))
                VisualModelOutline.close();
        }
    }

    private static void replay(ModelOverlayTarget storage, Set<CapturedDraw> values) {
        var target = storage.initAndGet();
        replaying = true;
        try (var pass =
                RenderSystem.getDevice()
                        .createCommandEncoder()
                        .createRenderPass(
                                () -> "Moons model visuals",
                                target.getColorTextureView(),
                                Optional.empty(),
                                target.getDepthTextureView(),
                                OptionalDouble.empty())) {
            for (CapturedDraw draw : values) draw.type().drawFromBuffer(draw.info(), pass);
        } finally {
            replaying = false;
        }
    }

    public static void close() {
        activePlayer = false;
        renderingItemSubmit = null;
        dirty = false;
        replaying = false;
        HELD_ITEM_SUBMITS.clear();
        OVERLAY_ITEM_SUBMITS.clear();
        draws.clear();
        ModelOverlayMaterials.clear();
        NORMAL_TARGET.close();
        for (ModelOverlayTarget target : OVERLAY_TARGETS.values()) target.close();
        OVERLAY_TARGETS.clear();
        VisualModelOutline.close();
    }
}
