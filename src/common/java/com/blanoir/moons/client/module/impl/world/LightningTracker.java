package com.blanoir.moons.client.module.impl.world;

import com.blanoir.moons.client.chat.ClientChat;
import com.blanoir.moons.client.config.settings.BooleanSetting;
import com.blanoir.moons.client.event.EventBus;

import net.minecraft.client.Minecraft;
import net.minecraft.network.play.server.S2CPacketSpawnGlobalEntity;

public final class LightningTracker {
    private static final BooleanSetting ENABLED =
            new BooleanSetting.Builder()
                    .name("lightningtracker.enabled")
                    .defaultValue(true)
                    .build();

    private static int count = 0;

    private LightningTracker() {}

    public static void initPacketListeners() {
        EventBus.PACKET_RECEIVE_APPLY.register(
                "LightningTracker.entityAdded",
                event -> {
                    if (event.packet() instanceof S2CPacketSpawnGlobalEntity packet) {
                        handlePacket(packet);
                    }
                });
    }

    public static int setEnabled(Minecraft client, boolean newEnabled) {
        ENABLED.set(newEnabled);
        ClientChat.send(client, "Lightning tracker " + statusText() + ".");
        return 1;
    }

    public static String statusText() {
        return ENABLED.get() ? "enabled" : "disabled";
    }

    public static int showStatus(Minecraft client) {
        ClientChat.send(
                client,
                "Lightning tracker: "
                        + statusText()
                        + ". Usage: .moons lightingtrack <enable|disable>");
        return 1;
    }

    public static void handlePacket(S2CPacketSpawnGlobalEntity packet) {
        if (!ENABLED.get()) {
            return;
        }

        Minecraft client = Minecraft.getMinecraft();
        var currentPlayer = client == null ? null : client.thePlayer;
        if (client == null || currentPlayer == null || client.theWorld == null) {
            return;
        }

        if (packet.func_149053_g() != 1) {
            return;
        }

        double x = packet.func_149051_d() / 32.0;
        double y = packet.func_149050_e() / 32.0;
        double z = packet.func_149049_f() / 32.0;
        double distance = Math.sqrt(currentPlayer.getDistanceSq(x, y, z));

        count++;

        ClientChat.send(
                client,
                "Lightning #"
                        + count
                        + " | Distance: "
                        + Math.round(distance)
                        + " | X: "
                        + Math.round(x)
                        + " Y: "
                        + Math.round(y)
                        + " Z: "
                        + Math.round(z));
    }
}
