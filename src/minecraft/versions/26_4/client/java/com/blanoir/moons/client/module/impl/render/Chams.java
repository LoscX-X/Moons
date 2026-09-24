package com.blanoir.moons.client.module.impl.render;

import com.blanoir.moons.client.access.MinecraftClientAccess;
import com.blanoir.moons.client.config.ClientBranding;
import com.blanoir.moons.client.config.settings.BooleanSetting;
import com.blanoir.moons.client.module.impl.render.chams.ChamsRenderLayers;
import com.blanoir.moons.client.module.impl.render.chams.ChamsRenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.function.BiPredicate;

/**
 * Offscreen entity highlighting.
 *
 * Eligible player render types (body, equipment, cape and held items) are remapped
 * into a private offscreen render target while the level is submitted.  After the
 * main pass finishes drawing the world, the accumulated color texture is blitted
 * over the main render target, so the player stays fully visible through walls.
 */
public final class Chams {
    public static final float CHAMS_RED = 1.0f;
    public static final float CHAMS_GREEN = 0.56f;
    public static final float CHAMS_BLUE = 0.84f;
    public static final float HIGHLIGHTER_ALPHA = 0.46f;

    private static final BooleanSetting HIGHLIGHTER_ENABLED =
            new BooleanSetting.Builder()
                    .name("chams.players.highlighter.enabled")
                    .defaultValue(true)
                    .build();
    private static final BooleanSetting CHAMS_ENABLED =
            new BooleanSetting.Builder().name("chams.players.enabled").defaultValue(true).build();

    private static final ChamsRenderTarget RENDER_TARGET =
            new ChamsRenderTarget(ClientBranding.name() + " Chams");
    private static final Set<Object> HELD_ITEM_SUBMITS =
            Collections.newSetFromMap(new IdentityHashMap<>());

    private static boolean activePlayerChams;
    private static boolean dirty;
    private static Object renderingItemSubmit;
    private static BiPredicate<Minecraft, Player> playerFilter = (client, player) -> false;

    private Chams() {}

    public static void bindPlayerFilter(BiPredicate<Minecraft, Player> filter) {
        playerFilter = filter;
    }

    private record CapturedDraw(PreparedRenderType type, StagedVertexBuffer.ExecuteInfo info) {}

    private static final Set<CapturedDraw> draws = new LinkedHashSet<>();
    private static boolean replaying;

    public static boolean captureDraw(Object type, Object info) {
        if (replaying
                || !dirty
                || !CHAMS_ENABLED.get()
                || !(type instanceof PreparedRenderType prepared)
                || !prepared.name().startsWith("moons_chams_")
                || !(info instanceof StagedVertexBuffer.ExecuteInfo execute)) return false;
        CapturedDraw draw = new CapturedDraw(prepared, execute);
        // OIT visits the same draw in several passes; replay its original pipeline once.
        draws.add(draw);
        return true;
    }

    public static void setHighlighterEnabled(boolean newEnabled) {
        HIGHLIGHTER_ENABLED.set(newEnabled);
    }

    public static void setChamsEnabled(boolean newEnabled) {
        CHAMS_ENABLED.set(newEnabled);
        if (!CHAMS_ENABLED.get()) {
            activePlayerChams = false;
            dirty = false;
            renderingItemSubmit = null;
            draws.clear();
            HELD_ITEM_SUBMITS.clear();
            RENDER_TARGET.close();
        }
    }

    public static boolean isHighlighterEnabled() {
        return HIGHLIGHTER_ENABLED.get();
    }

    public static boolean isChamsEnabled() {
        return CHAMS_ENABLED.get();
    }

    public static String highlighterStatusText() {
        return HIGHLIGHTER_ENABLED.get() ? "enabled" : "disabled";
    }

    public static String chamsStatusText() {
        return CHAMS_ENABLED.get() ? "enabled" : "disabled";
    }

    /**
     * Controls whether the AvatarRenderer (player entity) render pass is captured for
     * the chams offscreen target.
     */
    public static boolean shouldRenderEntityChamsFor(AvatarRenderState state) {
        if (!CHAMS_ENABLED.get() || state.isSpectator) {
            return false;
        }
        Minecraft client = Minecraft.getInstance();
        var currentPlayer = client == null ? null : client.player;
        var currentLevel = client == null ? null : client.level;

        if (currentLevel == null || currentPlayer == null) {
            return false;
        }

        if (MinecraftClientAccess.isHudHidden(client)) {
            return false;
        }

        Entity entity = currentLevel.getEntity(state.id);
        if (entity == currentPlayer) {
            return false;
        }

        return entity instanceof Player player && playerFilter.test(client, player);
    }

    public static void beginPlayerChams(AvatarRenderState state) {
        activePlayerChams = shouldRenderEntityChamsFor(state);
    }

    public static void endPlayerChams() {
        activePlayerChams = false;
    }

    /**
     * Remaps body/equipment/cape render types submitted while a shown player is being
     * submitted.
     */
    public static RenderType remapIfNeeded(RenderType renderType) {
        if (!activePlayerChams || !ChamsRenderLayers.supports(renderType)) {
            return renderType;
        }

        dirty = true;
        return ChamsRenderLayers.remap(renderType);
    }

    public static void markHeldItemSubmit(Object submit) {
        if (activePlayerChams && submit != null) {
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
        if (!CHAMS_ENABLED.get()
                || renderingItemSubmit == null
                || !HELD_ITEM_SUBMITS.contains(renderingItemSubmit)
                || !ChamsRenderLayers.supports(renderType)) {
            return renderType;
        }

        dirty = true;
        return ChamsRenderLayers.remap(renderType);
    }

    public static void beginFrameIfNeeded() {
        if (!CHAMS_ENABLED.get()) {
            return;
        }

        draws.clear();
    }

    public static void compositeIfNeeded() {
        if (!CHAMS_ENABLED.get()) {
            HELD_ITEM_SUBMITS.clear();
            dirty = false;
            return;
        }

        try {
            if (!dirty || draws.isEmpty()) {
                return;
            }

            var target = RENDER_TARGET.initAndGet();
            replaying = true;
            try (var pass =
                    RenderSystem.getDevice()
                            .createCommandEncoder()
                            .createRenderPass(
                                    () -> "Moons chams",
                                    target.getColorTextureView(),
                                    Optional.empty(),
                                    target.getDepthTextureView(),
                                    OptionalDouble.empty())) {
                for (CapturedDraw draw : draws) draw.type().drawFromBuffer(draw.info(), pass);
            } finally {
                replaying = false;
            }
            RENDER_TARGET.composite(
                    MinecraftClientAccess.mainRenderTarget(Minecraft.getInstance()));
        } finally {
            dirty = false;
            draws.clear();
            HELD_ITEM_SUBMITS.clear();
        }
    }

    public static void close() {
        draws.clear();
        RENDER_TARGET.close();
    }
}
