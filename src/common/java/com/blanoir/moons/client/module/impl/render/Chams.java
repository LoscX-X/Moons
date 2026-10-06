package com.blanoir.moons.client.module.impl.render;

import com.blanoir.moons.client.access.MinecraftClientAccess;
import com.blanoir.moons.client.config.settings.BooleanSetting;
import com.blanoir.moons.client.event.EventBus;
import com.blanoir.moons.client.render.VisualModelCapture;
import com.blanoir.moons.client.render.model.ModelOverlayRenderer;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.Vec3;

import java.util.function.BiPredicate;

/** EntityPlayer highlighting policy. GPU capture and additional models belong to the visual backend. */
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
    private static BiPredicate<Minecraft, EntityPlayer> playerFilter = (client, player) -> false;

    static {
        ModelOverlayRenderer.setNormalEnabled(CHAMS_ENABLED.get());
    }

    private Chams() {}

    public static void bindPlayerFilter(BiPredicate<Minecraft, EntityPlayer> filter) {
        playerFilter = filter;
        EventBus.WORLD_RENDER.register(
                "Chams.worldRender",
                event -> {
                    var client = Minecraft.getMinecraft();
                    if (client.theWorld == null) return;
                    float partial = event.tickDelta();
                    for (var player : client.theWorld.playerEntities) {
                        if (!shouldRenderEntityChamsFor(player)) continue;
                        Vec3 at =
                                new Vec3(
                                        player.lastTickPosX
                                                + (player.posX - player.lastTickPosX) * partial,
                                        player.lastTickPosY
                                                + (player.posY - player.lastTickPosY) * partial,
                                        player.lastTickPosZ
                                                + (player.posZ - player.lastTickPosZ) * partial);
                        ModelOverlayRenderer.render(player, at, partial, 0xffff8fd6, 1, true);
                    }
                });
    }

    public static boolean shouldRenderEntityChamsFor(EntityPlayer player) {
        var client = Minecraft.getMinecraft();
        return !VisualModelCapture.active()
                && CHAMS_ENABLED.get()
                && client.thePlayer != null
                && client.theWorld != null
                && !MinecraftClientAccess.isHudHidden(client)
                && playerFilter.test(client, player);
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
