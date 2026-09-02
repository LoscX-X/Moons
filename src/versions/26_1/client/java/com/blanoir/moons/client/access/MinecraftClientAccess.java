package com.blanoir.moons.client.access;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.MagmaCube;
import net.minecraft.world.entity.monster.Slime;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.scores.PlayerTeam;

public final class MinecraftClientAccess {
    private MinecraftClientAccess() {
    }

    public static Screen screen(Minecraft client) {
        return client.screen;
    }

    public static void setScreen(Minecraft client, Screen screen) {
        client.setScreen(screen);
    }

    public static Camera camera(Minecraft client) {
        return client.gameRenderer.getMainCamera();
    }

    public static RenderTarget mainRenderTarget(Minecraft client) {
        return client.getMainRenderTarget();
    }

    public static boolean isHudHidden(Minecraft client) {
        return client.options.hideGui;
    }

    public static void rebuildLevelRenderer(Minecraft client) {
        client.levelRenderer.allChanged();
    }

    public static PlayerTabOverlay tabList(Minecraft client) {
        return client.gui.getTabList();
    }

    public static String teamColorName(PlayerTeam team) {
        return team.getColor().getName();
    }

    public static void sendSystemMessage(Minecraft client, Component message, boolean overlay) {
        client.getChatListener().handleSystemMessage(message, overlay);
    }

    public static void refreshChat(Minecraft client) {
        if (client != null && client.gui != null) {
            client.gui.getChat().rescaleChat();
        }
    }

    public static boolean isChatOpen(Minecraft client) {
        return client != null && screen(client) instanceof ChatScreen;
    }

    public static boolean isLightning(EntityType<?> type) {
        return type == EntityType.LIGHTNING_BOLT;
    }

    public static boolean isMagmaCube(Entity entity) {
        return entity instanceof MagmaCube;
    }

    public static boolean isSlime(Entity entity) {
        return entity instanceof Slime;
    }

    public static Block[] copperBlocks() {
        return new Block[] {
                Blocks.COPPER_BLOCK,
                Blocks.EXPOSED_COPPER,
                Blocks.WEATHERED_COPPER,
                Blocks.OXIDIZED_COPPER,
                Blocks.WAXED_COPPER_BLOCK,
                Blocks.WAXED_EXPOSED_COPPER,
                Blocks.WAXED_WEATHERED_COPPER,
                Blocks.WAXED_OXIDIZED_COPPER
        };
    }
}
