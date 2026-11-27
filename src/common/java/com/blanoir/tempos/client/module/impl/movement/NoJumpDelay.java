package com.blanoir.tempos.client.module.impl.movement;

import com.blanoir.tempos.client.chat.ClientChat;
import com.blanoir.tempos.client.config.settings.BooleanSetting;
import com.blanoir.tempos.client.module.framework.ClientFeature;
import com.blanoir.tempos.client.module.framework.ModuleCategory;
import com.blanoir.tempos.client.module.framework.ModuleInfo;

import net.minecraft.client.Minecraft;

@ModuleInfo(
        category = ModuleCategory.MOVEMENT,
        description = "Removes the delay between consecutive jumps.")
public final class NoJumpDelay extends ClientFeature {
    public static final NoJumpDelay INSTANCE = new NoJumpDelay();

    private final BooleanSetting enabled =
            new BooleanSetting.Builder().name("nojumpdelay.enabled").defaultValue(false).build();

    private NoJumpDelay() {
        super("nojumpdelay", "NoJumpDelay");
    }

    @Override
    public boolean isEnabled() {
        return this.enabled.get();
    }

    @Override
    public int setEnabled(Minecraft client, boolean newEnabled) {
        this.enabled.set(newEnabled);
        ClientChat.send(
                client,
                "NoJumpDelay " + statusText() + ". Usage: .tempos nojumpdelay <enable|disable>");
        return 1;
    }

    public String statusText() {
        return this.enabled.get() ? "enabled" : "disabled";
    }
}
