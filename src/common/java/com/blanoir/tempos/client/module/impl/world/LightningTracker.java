package com.blanoir.tempos.client.module.impl.world;

import com.blanoir.tempos.client.access.MinecraftClientAccess;
import com.blanoir.tempos.client.chat.ClientChat;
import com.blanoir.tempos.client.config.Settings;
import com.blanoir.tempos.client.config.settings.BooleanSetting;
import com.blanoir.tempos.client.event.EventBus;
import com.blanoir.tempos.client.module.framework.ClientFeature;
import com.blanoir.tempos.client.module.framework.ModuleCategory;
import com.blanoir.tempos.client.module.framework.ModuleInfo;
import com.blanoir.tempos.client.module.framework.ModuleModel;

import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;

@ModuleInfo(
        category = ModuleCategory.EXPERIMENT,
        description = "Reports detected lightning strikes.")
public final class LightningTracker extends ClientFeature {
    public static final LightningTracker INSTANCE = new LightningTracker();

    private final BooleanSetting enabled =
            new BooleanSetting.Builder()
                    .name("lightningtracker.enabled")
                    .defaultValue(true)
                    .build();

    private int count = 0;

    private LightningTracker() {
        super("lightningtracker", "LightningTracker");
    }

    public void initPacketListeners() {
        EventBus.PACKET_RECEIVE_APPLY.register(
                "LightningTracker.entityAdded",
                event -> {
                    if (event.packet() instanceof ClientboundAddEntityPacket packet) {
                        handlePacket(packet);
                    }
                });
    }

    @Override
    public int setEnabled(Minecraft client, boolean newEnabled) {
        this.enabled.set(newEnabled);
        ClientChat.send(client, "Lightning tracker " + statusText() + ".");
        return 1;
    }

    public String statusText() {
        return this.enabled.get() ? "enabled" : "disabled";
    }

    public int showStatus(Minecraft client) {
        ClientChat.send(
                client,
                "Lightning tracker: "
                        + statusText()
                        + ". Usage: .tempos lightingtrack <enable|disable>");
        return 1;
    }

    public void handlePacket(ClientboundAddEntityPacket packet) {
        if (!this.enabled.get()) {
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

        this.count++;

        ClientChat.send(
                client,
                "Lightning #"
                        + this.count
                        + " | Distance: "
                        + Math.round(distance)
                        + " | X: "
                        + Math.round(x)
                        + " Y: "
                        + Math.round(y)
                        + " Z: "
                        + Math.round(z));
    }

    @Override
    public boolean isEnabled() {
        return Settings.getBoolean("lightningtracker.enabled", true);
    }

    @Override
    protected String statusTag() {
        return this.statusText();
    }

    @Override
    public ModuleModel.Module toDescriptor() {
        return super.toDescriptor().withDefaultEnabled(true);
    }
}
