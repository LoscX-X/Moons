package com.blanoir.moons.client.module.impl.misc;

import com.blanoir.moons.client.chat.ClientChat;
import com.blanoir.moons.client.config.settings.BooleanSetting;
import com.blanoir.moons.client.config.settings.DoubleSetting;

import net.minecraft.client.Minecraft;

/** Keeps the rendered world FOV independent from sprinting, items and status effects. */
public final class StaticFov {
    private static final BooleanSetting ENABLED =
            new BooleanSetting.Builder().name("staticfov.enabled").defaultValue(false).build();
    private static final DoubleSetting FOV =
            new DoubleSetting.Builder()
                    .name("staticfov.fov")
                    .defaultValue(90.0D)
                    .range(30.0D, 170.0D)
                    .build();

    private StaticFov() {}

    public static boolean isEnabled() {
        return ENABLED.get();
    }

    public static float apply(float vanillaFov) {
        return ENABLED.get() ? (float) FOV.get() : vanillaFov;
    }

    public static String hudTag() {
        return Integer.toString((int) Math.round(FOV.get()));
    }

    public static int setEnabled(Minecraft client, boolean enabled) {
        ENABLED.set(enabled);
        ClientChat.send(client, "StaticFov " + (enabled ? "enabled" : "disabled") + ".");
        return 1;
    }

    public static int setFov(Minecraft ignoredClient, double fov) {
        FOV.set(fov);
        return 1;
    }
}
