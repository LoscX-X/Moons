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

    public static net.minecraft.server.packs.PackResources vanillaResources(Minecraft client) {
        return client.getVanillaPackResources().fullResources();
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
            // 26.3 owns terrain invalidation in LevelExtractor. allChanged()
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
        return item instanceof net.minecraft.world.item.BlockItem block
                && block.getBlock() instanceof net.minecraft.world.level.block.BedBlock;
    }

    public static boolean isEnderman(Entity entity) {
        return entity instanceof net.minecraft.world.entity.monster.Enderman;
    }

    public static void swingAttackLocally(
            net.minecraft.client.player.LocalPlayer player,
            net.minecraft.world.InteractionHand hand) {
        player.swing(hand, player.getItemInHand(hand).getAttackAnimation(), false);
    }

    public static void animatePlacement(
            net.minecraft.client.player.LocalPlayer player,
            net.minecraft.world.InteractionHand hand,
            boolean visible) {
        // 26.3's use-item packet triggers server animation; Punch is an attack action.
        if (visible) player.swing(hand, player.getItemInHand(hand).getInteractAnimation(), false);
    }

    public static boolean isBindingKeyDown(
            Minecraft client, com.mojang.blaze3d.platform.InputConstants.Key key) {
        if (key == null || key == com.mojang.blaze3d.platform.InputConstants.UNKNOWN) return false;
        if (key.getType() == com.mojang.blaze3d.platform.InputConstants.Type.MOUSE
                && (key.getValue() < com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT
                        || key.getValue()
                                > com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_8))
            return false;
        // On the affected Windows/SDL path, physical right Shift never reaches SDL's
        // keyboard state or key callbacks. All Moons bindings share this physical-key fallback.
        if (key.getType() == com.mojang.blaze3d.platform.InputConstants.Type.KEYBOARD
                && key.getValue() == com.mojang.blaze3d.platform.InputConstants.KEY_RSHIFT
                && org.lwjgl.system.Platform.get() == org.lwjgl.system.Platform.WINDOWS) {
            return client.isWindowActive()
                    && (org.lwjgl.system.windows.User32.GetAsyncKeyState(
                                            org.lwjgl.system.windows.User32.VK_RSHIFT)
                                    & 0x8000)
                            != 0;
        }
        if (key.getType() == com.mojang.blaze3d.platform.InputConstants.Type.MOUSE) {
            return isHardwareKeyDown(client, key);
        }
        java.nio.ByteBuffer keyboard = org.lwjgl.sdl.SDLKeyboard.SDL_GetKeyboardState();
        return keyboard != null
                && key.getValue() >= 0
                && key.getValue() < keyboard.limit()
                && keyboard.get(key.getValue()) != 0;
    }

    public static boolean isHardwareKeyDown(
            Minecraft client, com.mojang.blaze3d.platform.InputConstants.Key key) {
        if (key.getType() == com.mojang.blaze3d.platform.InputConstants.Type.MOUSE) {
            return (org.lwjgl.sdl.SDLMouse.nSDL_GetMouseState(0L, 0L) & (1 << (key.getValue() - 1)))
                    != 0;
        }
        return com.mojang.blaze3d.platform.InputConstants.isKeyDown(key.getValue());
    }

    public static net.minecraft.resources.Identifier trimPaletteTexture(String palette) {
        return net.minecraft.resources.Identifier.withDefaultNamespace(
                "textures/palettes/"
                        + (palette.equals("trim_palette") ? "trim_base" : "trim/" + palette)
                        + ".png");
    }
}
