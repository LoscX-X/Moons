package com.blanoir.moons.client.module.impl.world;

import com.blanoir.moons.client.access.MinecraftClientAccess;
import com.blanoir.moons.client.config.settings.BooleanSetting;
import com.blanoir.moons.client.chat.ClientChat;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;

public final class LightningTracker {
    private static final BooleanSetting ENABLED =
            new BooleanSetting.Builder()
                    .name("lightningtracker.enabled")
                    .defaultValue(true)
                    .build();

    private static int count = 0;

    private LightningTracker() {
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
        ClientChat.send(client, "Lightning tracker: " + statusText() + ". Usage: .moons lightingtrack <enable|disable>");
        return 1;
    }

    public static void handlePacket(ClientboundAddEntityPacket packet) {
        if (!ENABLED.get()) {
            return;
        }

        Minecraft client = Minecraft.getInstance();
        var currentPlayer = client == null ? null : client.player;
        if (client == null || currentPlayer == null || client.level == null) {
            return;
        }

        if (!MinecraftClientAccess.isLightning(packet.getType())) {
            return;
        }

        double x = packet.getX();
        double y = packet.getY();
        double z = packet.getZ();
        double distance = Math.sqrt(currentPlayer.distanceToSqr(x, y, z));

        count++;

        ClientChat.send(
                client,
                "Lightning #" + count
                        + " | Distance: " + Math.round(distance)
                        + " | X: " + Math.round(x)
                        + " Y: " + Math.round(y)
                        + " Z: " + Math.round(z)
        );
    }
}
