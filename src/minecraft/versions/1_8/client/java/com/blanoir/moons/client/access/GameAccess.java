package com.blanoir.moons.client.access;

import com.blanoir.moons.client.compat.input.InputConstants;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiPlayerTabOverlay;
import net.minecraft.client.multiplayer.PlayerControllerMP;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.network.*;
import net.minecraft.network.play.server.S19PacketEntityStatus;
import net.minecraft.util.*;

public final class GameAccess {
    private static final String MC = "net.minecraft.client.Minecraft";

    private GameAccess() {}

    public static com.blanoir.moons.client.compat.input.InputSnapshot inputSnapshot(
            MovementInput input) {
        return new com.blanoir.moons.client.compat.input.InputSnapshot(
                input.moveForward > 0,
                input.moveForward < 0,
                input.moveStrafe > 0,
                input.moveStrafe < 0,
                input.jump,
                input.sneak,
                Minecraft.getMinecraft().gameSettings.keyBindSprint.isKeyDown());
    }

    public static void inputSnapshot(
            MovementInput input, com.blanoir.moons.client.compat.input.InputSnapshot value) {
        float scale = value.shift() ? .3F : 1F;
        input.moveForward = ((value.forward() ? 1 : 0) - (value.backward() ? 1 : 0)) * scale;
        input.moveStrafe = ((value.left() ? 1 : 0) - (value.right() ? 1 : 0)) * scale;
        input.jump = value.jump();
        input.sneak = value.shift();
    }

    public static void moveVector(MovementInput input, float sideways, float forward) {
        input.moveStrafe = sideways;
        input.moveForward = forward;
    }

    public static InputConstants.Key boundKey(KeyBinding key) {
        int code = key.getKeyCode();
        return code < 0
                ? InputConstants.Type.MOUSE.getOrCreate(code + 100)
                : InputConstants.fromKeyCode(code);
    }

    public static void invokeStartAttack(Minecraft client) {
        LegacyReflection.invoke(client, Minecraft.class, MC, "clickMouse");
    }

    public static void invokeStartUseItem(Minecraft client) {
        LegacyReflection.invoke(client, Minecraft.class, MC, "rightClickMouse");
    }

    public static int rightClickDelay(Minecraft client) {
        return LegacyReflection.get(client, Minecraft.class, MC, "rightClickDelayTimer");
    }

    public static void rightClickDelay(Minecraft client, int ticks) {
        LegacyReflection.set(
                client, Minecraft.class, MC, "rightClickDelayTimer", Math.max(0, ticks));
    }

    public static void syncCarriedItem(PlayerControllerMP controller) {
        LegacyReflection.invoke(
                controller,
                PlayerControllerMP.class,
                "net.minecraft.client.multiplayer.PlayerControllerMP",
                "syncCurrentPlayItem");
    }

    public static void clearJumpDelay(EntityLivingBase entity) {
        LegacyReflection.set(
                entity,
                EntityLivingBase.class,
                "net.minecraft.entity.EntityLivingBase",
                "jumpTicks",
                0);
    }

    public static int entityEventId(S19PacketEntityStatus packet) {
        return LegacyReflection.get(
                packet,
                S19PacketEntityStatus.class,
                "net.minecraft.network.play.server.S19PacketEntityStatus",
                "entityId");
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void dispatchIncoming(Packet packet, INetHandler listener) {
        try {
            packet.processPacket(listener);
        } catch (ThreadQuickExitException ignored) {
            /* PacketThreadUtil scheduled game-thread processing. */
        }
    }

    public static IChatComponent tabHeader(GuiPlayerTabOverlay overlay) {
        return LegacyReflection.get(
                overlay,
                GuiPlayerTabOverlay.class,
                "net.minecraft.client.gui.GuiPlayerTabOverlay",
                "header");
    }

    public static IChatComponent tabFooter(GuiPlayerTabOverlay overlay) {
        return LegacyReflection.get(
                overlay,
                GuiPlayerTabOverlay.class,
                "net.minecraft.client.gui.GuiPlayerTabOverlay",
                "footer");
    }

    public static net.minecraft.util.Timer timer(Minecraft client) {
        return LegacyReflection.get(client, Minecraft.class, MC, "timer");
    }
}
