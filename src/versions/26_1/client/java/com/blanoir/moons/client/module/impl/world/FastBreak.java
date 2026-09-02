package com.blanoir.moons.client.module.impl.world;

import com.blanoir.moons.client.config.settings.BooleanSetting;
import com.blanoir.moons.client.config.settings.ModeSetting;
import com.blanoir.moons.client.chat.ClientChat;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.world.item.ItemStack;

import java.util.List;

public final class FastBreak {
    private static final BooleanSetting ENABLED =
            new BooleanSetting.Builder()
                    .name("fastbreak.enabled")
                    .defaultValue(false)
                    .build();

    private static final BooleanSetting ONLY_TOOL =
            new BooleanSetting.Builder()
                    .name("fastbreak.onlyTool")
                    .defaultValue(false)
                    .build();

    private static final ModeSetting<Mode> MODE =
            new ModeSetting.Builder<Mode>()
                    .name("fastbreak.mode")
                    .defaultValue(Mode.ABORT_ANOTHER)
                    .option(Mode.ABORT_ANOTHER, "abort_another", "abortanother")
                    .option(Mode.OFF, "off", "disabled")
                    .build();

    private FastBreak() {
    }

    public static void init() {
    }

    public static int showStatus(Minecraft client) {
        ClientChat.send(client, "FastBreak: " + statusText()
                + ", mode: " + MODE.serialized()
                + ", onlyTool: " + toggleText(ONLY_TOOL.get())
                + ". Usage: .moons fastbreak <enable|disable|mode abortanother|off|onlytool enable|disable>");
        return 1;
    }

    public static int setEnabled(Minecraft client, boolean newEnabled) {
        ENABLED.set(newEnabled);
        ClientChat.send(client, "FastBreak " + statusText() + ".");
        return 1;
    }

    public static int setOnlyTool(Minecraft client, boolean newEnabled) {
        ONLY_TOOL.set(newEnabled);
        ClientChat.send(client, "FastBreak onlyTool " + toggleText(ONLY_TOOL.get()) + ".");
        return 1;
    }

    public static int setMode(Minecraft client, String value) {
        MODE.deserialize(value);
        ClientChat.send(client, "FastBreak mode set to " + MODE.serialized() + ".");
        return 1;
    }

    public static void handleOutgoingPacket(Packet<?> packet) {
        Minecraft client = Minecraft.getInstance();
        if (!ENABLED.get() || MODE.get() == Mode.OFF || client == null || client.player == null || client.level == null) {
            return;
        }

        if (ONLY_TOOL.get() && !isMiningTool(client.player.getMainHandItem())) {
            return;
        }

        if (client.player == null || client.player.connection == null) {
            return;
        }

        if (MODE.get() == Mode.ABORT_ANOTHER
                && packet instanceof ServerboundPlayerActionPacket actionPacket
                && actionPacket.getAction() == ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK) {

            client.player.connection.send(new ServerboundPlayerActionPacket(
                    ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK,
                    actionPacket.getPos().above(),
                    actionPacket.getDirection(),
                    actionPacket.getSequence()
            ));
        }
    }

    public static String statusText() {
        return ENABLED.get() ? "enabled" : "disabled";
    }

    public static List<String> modeOptions() { return MODE.optionIds(); }

    private static boolean isMiningTool(ItemStack stack) {
        return stack.isCorrectToolForDrops(Minecraft.getInstance().level.getBlockState(Minecraft.getInstance().player.blockPosition().below()))
                || stack.getItem().toString().contains("pickaxe")
                || stack.getItem().toString().contains("axe")
                || stack.getItem().toString().contains("shovel")
                || stack.getItem().toString().contains("hoe");
    }

    private static String toggleText(boolean value) {
        return value ? "enabled" : "disabled";
    }

    private enum Mode {
        OFF,
        ABORT_ANOTHER
    }
}
