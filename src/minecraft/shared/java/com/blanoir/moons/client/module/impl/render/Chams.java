package com.blanoir.moons.client.module.impl.render;

import com.blanoir.moons.client.access.MinecraftClientAccess;
import com.blanoir.moons.client.config.ClientBranding;
import com.blanoir.moons.client.config.settings.BooleanSetting;
import com.blanoir.moons.client.module.impl.render.chams.ChamsRenderLayers;
import com.blanoir.moons.client.module.impl.render.chams.ChamsRenderTarget;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.rendertype.OutputTarget;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.function.BiPredicate;

/**
 * LiquidBounce-style Chams.
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

    public static OutputTarget chamsOutputTarget() {
        return RENDER_TARGET.outputTarget();
    }

    public static void setHighlighterEnabled(boolean newEnabled) {
        HIGHLIGHTER_ENABLED.set(newEnabled);
    }

    public static void setChamsEnabled(boolean newEnabled) {
        CHAMS_ENABLED.set(newEnabled);
        if (!CHAMS_ENABLED.get()) {
            activePlayerChams = false;
            dirty = false;
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
        Minecraft client = Minecraft.getInstance();
        var currentPlayer = client == null ? null : client.player;
        var currentLevel = client == null ? null : client.level;

        if (!CHAMS_ENABLED.get() || currentLevel == null || currentPlayer == null) {
            return false;
        }

        if (MinecraftClientAccess.isHudHidden(client)) {
            return false;
        }

        if (state.isSpectator) {
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
        return ChamsRenderLayers.remap(renderType, chamsOutputTarget());
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
        return ChamsRenderLayers.remap(renderType, chamsOutputTarget());
    }

    public static void beginFrameIfNeeded() {
        if (!CHAMS_ENABLED.get()) {
            return;
        }

        RENDER_TARGET.initAndGet();
    }

    public static void compositeIfNeeded() {
        if (!CHAMS_ENABLED.get()) {
            HELD_ITEM_SUBMITS.clear();
            dirty = false;
            return;
        }

        try {
            if (!dirty) {
                return;
            }

            RENDER_TARGET.composite(
                    MinecraftClientAccess.mainRenderTarget(Minecraft.getInstance()));
        } finally {
            dirty = false;
            HELD_ITEM_SUBMITS.clear();
        }
    }

    public static void close() {
        RENDER_TARGET.close();
    }
}
