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
    private static final java.util.Map<Integer, Integer> SCANCODE_KEYS = new java.util.HashMap<>();

    private MinecraftClientAccess() {}

    public static net.minecraft.server.packs.PackResources vanillaResources(Minecraft client) {
        return client.getVanillaPackResources();
    }

    public static boolean hasOverlay(Minecraft client) {
        return client.gui.overlay() != null;
    }

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

    public static boolean isBedItem(net.minecraft.world.item.Item item) {
        return item instanceof net.minecraft.world.item.BedItem;
    }

    public static boolean isEnderman(Entity entity) {
        return entity instanceof net.minecraft.world.entity.monster.EnderMan;
    }

    public static void swingAttackLocally(
            net.minecraft.client.player.LocalPlayer player,
            net.minecraft.world.InteractionHand hand) {
        player.swing(hand, false);
    }

    public static void animatePlacement(
            net.minecraft.client.player.LocalPlayer player,
            net.minecraft.world.InteractionHand hand,
            boolean visible) {
        if (visible) player.swing(hand);
        else
            player.connection.send(
                    new net.minecraft.network.protocol.game.ServerboundSwingPacket(hand));
    }

    public static boolean isBindingKeyDown(
            Minecraft client, com.mojang.blaze3d.platform.InputConstants.Key key) {
        if (key == null || key == com.mojang.blaze3d.platform.InputConstants.UNKNOWN) return false;
        if (key.getType() == com.mojang.blaze3d.platform.InputConstants.Type.SCANCODE) {
            int keyCode =
                    SCANCODE_KEYS.computeIfAbsent(
                            key.getValue(), MinecraftClientAccess::keyForScancode);
            return keyCode >= org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE
                    && com.mojang.blaze3d.platform.InputConstants.isKeyDown(
                            client.getWindow(), keyCode);
        }
        int minimum =
                key.getType() == com.mojang.blaze3d.platform.InputConstants.Type.MOUSE
                        ? org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_1
                        : org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE;
        int maximum =
                key.getType() == com.mojang.blaze3d.platform.InputConstants.Type.MOUSE
                        ? org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LAST
                        : org.lwjgl.glfw.GLFW.GLFW_KEY_LAST;
        if (key.getValue() < minimum || key.getValue() > maximum) return false;
        return isHardwareKeyDown(client, key);
    }

    private static int keyForScancode(int scancode) {
        // Only ask GLFW about named keys, never arbitrary gaps in its key-code range.
        for (java.lang.reflect.Field field :
                com.mojang.blaze3d.platform.InputConstants.class.getFields()) {
            if (!field.getName().startsWith("KEY_") || field.getType() != int.class) continue;
            try {
                int candidate = field.getInt(null);
                if (candidate >= org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE
                        && candidate <= org.lwjgl.glfw.GLFW.GLFW_KEY_LAST
                        && org.lwjgl.glfw.GLFW.glfwGetKeyScancode(candidate) == scancode)
                    return candidate;
            } catch (IllegalAccessException ignored) {
                // Public constants are normally accessible; keep an unsupported key unpressed.
            }
        }
        return org.lwjgl.glfw.GLFW.GLFW_KEY_UNKNOWN;
    }

    public static boolean isHardwareKeyDown(
            Minecraft client, com.mojang.blaze3d.platform.InputConstants.Key key) {
        if (key.getType() == com.mojang.blaze3d.platform.InputConstants.Type.MOUSE) {
            return org.lwjgl.glfw.GLFW.glfwGetMouseButton(
                            client.getWindow().handle(), key.getValue())
                    == com.mojang.blaze3d.platform.InputConstants.PRESS;
        }
        return com.mojang.blaze3d.platform.InputConstants.isKeyDown(
                client.getWindow(), key.getValue());
    }

    public static net.minecraft.resources.Identifier trimPaletteTexture(String palette) {
        return net.minecraft.resources.Identifier.withDefaultNamespace(
                "textures/trims/color_palettes/" + palette + ".png");
    }
}
