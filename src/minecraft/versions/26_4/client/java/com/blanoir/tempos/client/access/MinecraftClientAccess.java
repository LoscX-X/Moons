package com.blanoir.tempos.client.access;

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

    public static net.minecraft.client.gui.GuiPipeline guiTexturedPipeline() { return net.minecraft.client.gui.GuiPipeline.TEXTURED; }

    public static net.minecraft.client.gui.GuiPipeline guiPremultipliedPipeline() { return net.minecraft.client.gui.GuiPipeline.TEXTURED_PREMULTIPLIED_ALPHA; }

    public static java.util.List<net.minecraft.network.chat.Component> flatComponents(net.minecraft.network.chat.Component component) {
        var parts = new java.util.ArrayList<net.minecraft.network.chat.Component>();
        component.visitUnbounded((style, text) -> {
            if (!text.isEmpty()) parts.add(net.minecraft.network.chat.Component.literal(text).withStyle(style));
            return java.util.Optional.empty();
        }, net.minecraft.network.chat.Style.EMPTY);
        return java.util.List.copyOf(parts);
    }

    public static <T> java.util.Optional<T> visitStyled(net.minecraft.network.chat.Component component,
            net.minecraft.network.chat.FormattedText.StyledContentConsumer<T> visitor, net.minecraft.network.chat.Style style) {
        return component.visitUnbounded(visitor, style);
    }

    public static java.util.Optional<net.minecraft.world.phys.Vec3> playerImpulse(
            net.minecraft.network.protocol.Packet<?> packet, int entityId) {
        return packet instanceof net.minecraft.network.protocol.game.ClientboundAddEntityMotionPacket motion
                && motion.id() == entityId ? java.util.Optional.of(motion.impulse()) : java.util.Optional.empty();
    }


    /** Transfers the GPU texture to Minecraft and releases the uploaded CPU pixels. */
    public static void registerDynamicTexture(
            net.minecraft.resources.Identifier id,
            com.mojang.blaze3d.platform.NativeImage image,
            String label) {
        net.minecraft.client.renderer.texture.TextureResources texture = null;
        try (image) {
            texture =
                    net.minecraft.client.renderer.texture.TextureResources.from2dImage(
                            () -> label, image);
            Minecraft.getInstance().getTextureManager().registerProvider(id, new net.minecraft.client.renderer.texture.ExternalTextureProvider(texture));
        } catch (RuntimeException | Error failure) {
            if (texture != null) texture.close();
            throw failure;
        }
    }

    public static void piercingAttack(
            Minecraft client, net.minecraft.world.item.component.PiercingWeapon weapon) {
        client.gameMode.piercingAttack(
                client.player.getMainHandItem().getAttackAnimation(), weapon);
    }

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
            // 26.4-pre-2 owns terrain invalidation in LevelExtractor. allChanged()
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
        // 26.4-pre-2's use-item packet triggers server animation; Punch is an attack action.
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
        // keyboard state or key callbacks. All Tempos bindings share this physical-key fallback.
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
}
