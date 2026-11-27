package com.blanoir.tempos.client.module.impl.world;


import com.blanoir.tempos.client.chat.ClientChat;
import com.blanoir.tempos.client.config.Settings;
import com.blanoir.tempos.client.config.settings.BooleanSetting;
import com.blanoir.tempos.client.config.settings.ModeSetting;
import com.blanoir.tempos.client.event.EventBus;
import com.blanoir.tempos.client.module.framework.ClientFeature;
import com.blanoir.tempos.client.module.framework.ModuleCategory;
import com.blanoir.tempos.client.module.framework.ModuleInfo;
import com.blanoir.tempos.client.module.framework.ModuleModel.Setting;

import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.world.item.ItemStack;

@ModuleInfo(
        category = ModuleCategory.WORLD,
        description = "Adjusts block-breaking behavior for the selected mode and tool.")
public final class FastBreak extends ClientFeature {
    public static final FastBreak INSTANCE = new FastBreak();

    private final BooleanSetting enabled =
            new BooleanSetting.Builder().name("fastbreak.enabled").defaultValue(false).build();

    private final BooleanSetting onlyTool =
            new BooleanSetting.Builder().name("fastbreak.onlyTool").defaultValue(false).build();

    private final ModeSetting<Mode> mode =
            new ModeSetting.Builder<Mode>()
                    .name("fastbreak.mode")
                    .defaultValue(Mode.ABORT_ANOTHER)
                    .option(Mode.ABORT_ANOTHER, "abort_another")
                    .option(Mode.OFF, "off")
                    .build();

    private FastBreak() {
        super("fastbreak", "FastBreak");
    }

    public void initPacketListeners() {
        EventBus.PACKET_SEND_PRE.register(
                "FastBreak.packetSend", event -> handleOutgoingPacket(event.packet()));
    }

    public int showStatus(Minecraft client) {
        ClientChat.send(
                client,
                "FastBreak: "
                        + statusText()
                        + ", mode: "
                        + this.mode.serialized()
                        + ", onlyTool: "
                        + toggleText(this.onlyTool.get())
                        + ". Usage: .tempos fastbreak <enable|disable|mode abortanother|off|onlytool enable|disable>");
        return 1;
    }

    @Override
    public int setEnabled(Minecraft client, boolean newEnabled) {
        this.enabled.set(newEnabled);
        ClientChat.send(client, "FastBreak " + statusText() + ".");
        return 1;
    }

    public int setOnlyTool(Minecraft client, boolean newEnabled) {
        this.onlyTool.set(newEnabled);
        ClientChat.send(client, "FastBreak onlyTool " + toggleText(this.onlyTool.get()) + ".");
        return 1;
    }

    public int setMode(Minecraft client, String value) {
        this.mode.deserialize(value);
        ClientChat.send(client, "FastBreak mode set to " + this.mode.serialized() + ".");
        return 1;
    }

    public void handleOutgoingPacket(Packet<?> packet) {
        Minecraft client = Minecraft.getInstance();
        var currentPlayer = client == null ? null : client.player;
        if (!this.enabled.get()
                || this.mode.get() == Mode.OFF
                || client == null
                || currentPlayer == null
                || client.level == null) {
            return;
        }

        if (this.onlyTool.get() && !isMiningTool(currentPlayer.getMainHandItem())) {
            return;
        }

        if (currentPlayer.connection == null) {
            return;
        }

        if (this.mode.get() == Mode.ABORT_ANOTHER
                && packet instanceof ServerboundPlayerActionPacket actionPacket
                && actionPacket.getAction()
                        == ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK) {

            currentPlayer.connection.send(
                    new ServerboundPlayerActionPacket(
                            ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK,
                            actionPacket.getPos().above(),
                            actionPacket.getDirection(),
                            actionPacket.getSequence()));
        }
    }

    public String statusText() {
        return this.enabled.get() ? "enabled" : "disabled";
    }

    private static boolean isMiningTool(ItemStack stack) {
        return stack.isCorrectToolForDrops(
                        Minecraft.getInstance()
                                .level
                                .getBlockState(
                                        Minecraft.getInstance().player.blockPosition().below()))
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

    @Override
    public boolean isEnabled() {
        return Settings.getBoolean("fastbreak.enabled", false);
    }

    @Override
    protected String statusTag() {
        return this.statusText();
    }

    @Override
    protected Setting[] defineSettings() {
        return new Setting[] {
            onlyTool.describeStored("only_tool", "Only tool", this::setOnlyTool),
            mode.describeStored("mode", "Mode", this::setMode)
        };
    }
}
