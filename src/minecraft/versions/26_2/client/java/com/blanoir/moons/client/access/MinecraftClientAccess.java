package com.blanoir.moons.client.access;

import com.mojang.blaze3d.pipeline.RenderTarget;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.monster.cubemob.MagmaCube;
import net.minecraft.world.entity.monster.cubemob.Slime;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.PlayerTeam;

public final class MinecraftClientAccess {
    private MinecraftClientAccess() {}

    public static Screen screen(Minecraft client) {
        return client.gui.screen();
    }

    public static void setScreen(Minecraft client, Screen screen) {
        client.gui.setScreen(screen);
    }

    public static Camera camera(Minecraft client) {
        return client.gameRenderer.mainCamera();
    }

    public static RenderTarget mainRenderTarget(Minecraft client) {
        return client.gameRenderer.mainRenderTarget();
    }

    public static boolean isHudHidden(Minecraft client) {
        return client.gui.hud.isHidden();
    }

    public static void rebuildLevelRenderer(Minecraft client) {
        if (client != null && client.level != null) {
            // 26.2 owns terrain invalidation in LevelExtractor. allChanged()
            // rebuilds its SectionUpdateTracker with every section dirty and
            // defers compiled-geometry invalidation to the next extract pass.
            client.levelExtractor.allChanged();
        }
    }

    public static PlayerTabOverlay tabList(Minecraft client) {
        return client.gui.hud.getTabList();
    }

    public static String teamColorName(PlayerTeam team) {
        return team.getColor().map(color -> color.getSerializedName()).orElse("reset");
    }

    /** Resolves the optional team sidebar slot using this version's color API. */
    public static DisplaySlot teamDisplaySlot(PlayerTeam team) {
        return team.getColor().map(color -> color.displaySlot()).orElse(null);
    }

    public static void sendSystemMessage(Minecraft client, Component message, boolean overlay) {
        if (overlay) {
            client.gui.hud.setOverlayMessage(message, false);
        } else {
            client.gui.hud.getChat().addClientSystemMessage(message);
        }
    }

    public static void refreshChat(Minecraft client) {
        if (client != null && client.gui != null && client.gui.hud != null) {
            client.gui.hud.getChat().rescaleChat();
        }
    }

    public static boolean isChatOpen(Minecraft client) {
        return client != null && screen(client) instanceof ChatScreen;
    }

    public static boolean isLightning(EntityType<?> type) {
        return type == EntityTypes.LIGHTNING_BOLT;
    }

    public static boolean isMagmaCube(Entity entity) {
        return entity instanceof MagmaCube;
    }

    public static boolean isSlime(Entity entity) {
        return entity instanceof Slime;
    }

    public static Block[] copperBlocks() {
        return new Block[] {
            Blocks.COPPER_BLOCK.weathering().unaffected(),
            Blocks.COPPER_BLOCK.weathering().exposed(),
            Blocks.COPPER_BLOCK.weathering().weathered(),
            Blocks.COPPER_BLOCK.weathering().oxidized(),
            Blocks.COPPER_BLOCK.waxed().unaffected(),
            Blocks.COPPER_BLOCK.waxed().exposed(),
            Blocks.COPPER_BLOCK.waxed().weathered(),
            Blocks.COPPER_BLOCK.waxed().oxidized()
        };
    }
}
