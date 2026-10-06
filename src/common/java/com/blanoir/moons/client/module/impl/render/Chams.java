package com.blanoir.moons.client.module.impl.render;

import com.blanoir.moons.client.access.MinecraftClientAccess;
import com.blanoir.moons.client.config.settings.BooleanSetting;
import com.blanoir.moons.client.render.VisualModelCapture;
import com.blanoir.moons.client.render.model.ModelOverlayRenderer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.player.Player;

import java.util.function.BiPredicate;

/** Player highlighting policy. GPU capture and additional models belong to the visual backend. */
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
    private static BiPredicate<Minecraft, Player> playerFilter = (client, player) -> false;

    static {
        ModelOverlayRenderer.setNormalEnabled(CHAMS_ENABLED.get());
    }

    private Chams() {}

    public static void bindPlayerFilter(BiPredicate<Minecraft, Player> filter) {
        playerFilter = filter;
    }

    public static boolean shouldRenderEntityChamsFor(AvatarRenderState state) {
        if (VisualModelCapture.active()) return !state.isSpectator;
        if (!CHAMS_ENABLED.get() || state.isSpectator) return false;
        Minecraft client = Minecraft.getInstance();
        if (client == null
                || client.level == null
                || client.player == null
                || MinecraftClientAccess.isHudHidden(client)) return false;
        var entity = client.level.getEntity(state.id);
        return entity != client.player
                && entity instanceof Player player
                && playerFilter.test(client, player);
    }

    public static void beginPlayerChams(AvatarRenderState state) {
        ModelOverlayRenderer.beginPlayer(shouldRenderEntityChamsFor(state));
    }

    public static void setHighlighterEnabled(boolean value) {
        HIGHLIGHTER_ENABLED.set(value);
    }

    public static void setChamsEnabled(boolean value) {
        CHAMS_ENABLED.set(value);
        ModelOverlayRenderer.setNormalEnabled(CHAMS_ENABLED.get());
    }

    public static boolean isHighlighterEnabled() {
        return HIGHLIGHTER_ENABLED.get();
    }

    public static boolean isChamsEnabled() {
        return CHAMS_ENABLED.get();
    }

    public static String highlighterStatusText() {
        return isHighlighterEnabled() ? "enabled" : "disabled";
    }

    public static String chamsStatusText() {
        return isChamsEnabled() ? "enabled" : "disabled";
    }

    public static void close() {
        ModelOverlayRenderer.close();
    }
}
