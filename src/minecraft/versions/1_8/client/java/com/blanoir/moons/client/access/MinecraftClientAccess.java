package com.blanoir.moons.client.access;

import com.blanoir.moons.client.compat.input.InputConstants;
import com.blanoir.moons.client.render.LegacyCamera;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.*;
import net.minecraft.client.resources.IResourcePack;
import net.minecraft.entity.Entity;
import net.minecraft.entity.effect.EntityLightningBolt;
import net.minecraft.entity.monster.*;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.network.play.client.C0APacketAnimation;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.util.IChatComponent;

public final class MinecraftClientAccess {
    private MinecraftClientAccess() {}

    public static GuiScreen screen(Minecraft client) {
        return client.currentScreen;
    }

    public static GuiScreen currentScreen(Minecraft client) {
        return client.currentScreen;
    }

    public static void setScreen(Minecraft client, GuiScreen screen) {
        client.displayGuiScreen(screen);
    }

    public static boolean hasOverlay(Minecraft client) {
        return client.loadingScreen != null && client.currentScreen instanceof GuiDownloadTerrain;
    }

    public static LegacyCamera camera(Minecraft client) {
        return new LegacyCamera(framePartialTick(client));
    }

    public static float framePartialTick(Minecraft client) {
        return GameAccess.timer(client).renderPartialTicks;
    }

    public static net.minecraft.client.shader.Framebuffer mainRenderTarget(Minecraft client) {
        return client.getFramebuffer();
    }

    public static boolean isHudHidden(Minecraft client) {
        return client.gameSettings.hideGUI;
    }

    public static void rebuildLevelRenderer(Minecraft client) {
        client.renderGlobal.loadRenderers();
    }

    public static GuiPlayerTabOverlay tabList(Minecraft client) {
        return client.ingameGUI.getTabList();
    }

    public static String teamColorName(ScorePlayerTeam team) {
        return team.getChatFormat().getFriendlyName();
    }

    public static int teamDisplaySlot(ScorePlayerTeam team) {
        int color = team.getChatFormat().getColorIndex();
        return color < 0 ? -1 : 3 + color;
    }

    public static void sendSystemMessage(
            Minecraft client, IChatComponent message, boolean overlay) {
        if (overlay) client.ingameGUI.setRecordPlaying(message, false);
        else client.ingameGUI.getChatGUI().printChatMessage(message);
    }

    public static void refreshChat(Minecraft client) {
        if (client != null && client.ingameGUI != null) client.ingameGUI.getChatGUI().refreshChat();
    }

    public static boolean isChatOpen(Minecraft client) {
        return client != null && client.currentScreen instanceof GuiChat;
    }

    public static boolean isLightning(Entity entity) {
        return entity instanceof EntityLightningBolt;
    }

    public static boolean isMagmaCube(Entity entity) {
        return entity instanceof EntityMagmaCube;
    }

    public static boolean isSlime(Entity entity) {
        return entity instanceof EntitySlime;
    }

    public static boolean isEnderman(Entity entity) {
        return entity instanceof EntityEnderman;
    }

    public static boolean isBedItem(Item item) {
        return item == Items.bed;
    }

    public static void animatePlacement(EntityPlayerSP player, boolean visible) {
        if (visible) player.swingItem();
        else player.sendQueue.addToSendQueue(new C0APacketAnimation());
    }

    public static void swingAttackLocally(EntityPlayerSP player) {
        player.swingItem();
    }

    public static boolean isBindingKeyDown(Minecraft client, InputConstants.Key key) {
        return InputConstants.isKeyDown(key);
    }

    public static boolean isHardwareKeyDown(Minecraft client, InputConstants.Key key) {
        return InputConstants.isKeyDown(key);
    }

    public static IResourcePack vanillaResources(Minecraft client) {
        return LegacyReflection.get(
                client, Minecraft.class, "net.minecraft.client.Minecraft", "mcDefaultResourcePack");
    }
}
