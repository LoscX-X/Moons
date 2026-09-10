package com.blanoir.moons.client.module.impl.misc.antibot;

import com.blanoir.moons.client.chat.ClientChat;
import com.blanoir.moons.client.config.settings.BooleanSetting;
import com.blanoir.moons.client.config.settings.ModeSetting;
import com.blanoir.moons.client.event.EventBus;
import com.blanoir.moons.client.management.targeting.Targeting;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import java.util.List;

/** Unified bot filtering shared by every combat target selector. */
public final class AntiBot {
    private static final AntiBotState STATE = new AntiBotState();
    private static final BooleanSetting ENABLED =
            new BooleanSetting.Builder().name("antibot.enabled").defaultValue(false).build();
    // All retired mode IDs, including Custom, resolve to the single Advanced option.
    private static final ModeSetting<String> MODE =
            new ModeSetting.Builder<String>()
                    .name("antibot.mode")
                    .defaultValue("advanced")
                    .option("advanced", "advanced")
                    .build();
    private static boolean initialized;

    private AntiBot() {}

    public static synchronized void init() {
        if (initialized) return;
        initialized = true;
        Targeting.setIgnoreCheck(AntiBot::isBot);
        EventBus.TICK.register("AntiBot.tick", event -> STATE.tick(event.client()));
        EventBus.PACKET_RECEIVE_APPLY.register(
                "AntiBot.packetReceiveApply",
                event -> STATE.handlePacket(Minecraft.getInstance(), event.packet()));
    }

    public static boolean isBot(Entity entity) {
        Minecraft client = Minecraft.getInstance();
        if (!isEnabled() || client.player == null || entity == client.player || !(entity instanceof Player player)) return false;
        return STATE.isBot(client, player);
    }

    public static boolean isEnabled() {
        return ENABLED.get();
    }

    public static String hudTag() {
        return MODE.serialized();
    }

    public static List<String> modeOptions() {
        return MODE.optionIds();
    }

    public static int setEnabled(Minecraft client, boolean value) {
        if (ENABLED.get() != value) STATE.reset();
        ENABLED.set(value);
        ClientChat.send(client, "AntiBot " + (value ? "enabled" : "disabled") + ".");
        return 1;
    }

    public static int setMode(Minecraft ignoredClient, String value) {
        MODE.deserialize(value);
        return 1;
    }
}
