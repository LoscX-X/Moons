package com.blanoir.tempos.client.module.impl.misc;

import com.blanoir.tempos.client.chat.ClientChat;
import com.blanoir.tempos.client.config.settings.BooleanSetting;
import com.blanoir.tempos.client.config.settings.DoubleSetting;
import com.blanoir.tempos.client.module.framework.ClientFeature;
import com.blanoir.tempos.client.module.framework.ModuleCategory;
import com.blanoir.tempos.client.module.framework.ModuleInfo;
import com.blanoir.tempos.client.module.framework.ModuleModel.Setting;

import net.minecraft.client.Minecraft;

/** Keeps the rendered world FOV independent of sprinting, items and status effects. */
@ModuleInfo(
        category = ModuleCategory.MISC,
        description = "Keeps the world field of view at a fixed value.")
public final class StaticFov extends ClientFeature {
    public static final StaticFov INSTANCE = new StaticFov();

    private final BooleanSetting enabled =
            new BooleanSetting.Builder().name("staticfov.enabled").defaultValue(false).build();
    private final DoubleSetting fov =
            new DoubleSetting.Builder()
                    .name("staticfov.fov")
                    .defaultValue(90.0D)
                    .range(30.0D, 170.0D)
                    .build();

    private StaticFov() {
        super("staticfov", "StaticFov");
    }

    @Override
    public boolean isEnabled() {
        return this.enabled.get();
    }

    public float apply(float vanillaFov) {
        return this.enabled.get() ? (float) this.fov.get() : vanillaFov;
    }

    public String hudTag() {
        return Integer.toString((int) Math.round(this.fov.get()));
    }

    @Override
    public int setEnabled(Minecraft client, boolean enabled) {
        this.enabled.set(enabled);
        ClientChat.send(client, "StaticFov " + (enabled ? "enabled" : "disabled") + ".");
        return 1;
    }

    public int setFov(Minecraft ignoredClient, double fov) {
        this.fov.set(fov);
        return 1;
    }

    @Override
    protected String statusTag() {
        return this.hudTag();
    }

    @Override
    protected Setting[] defineSettings() {
        return new Setting[] {fov.describeStored("fov", "FOV", 1, this::setFov)};
    }
}
