package com.blanoir.moons.client.module.impl.render;

import com.blanoir.moons.client.config.settings.BooleanSetting;
import com.blanoir.moons.client.config.settings.IntSetting;
import com.blanoir.moons.client.config.settings.ModeSetting;
import com.blanoir.moons.client.event.EventBus;
import com.blanoir.moons.client.event.tick.TickEvent;
import com.blanoir.moons.client.chat.ClientChat;
import net.minecraft.client.Minecraft;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;

import java.util.List;

/**
 * LiquidBounce-style FullBright.
 *
 * Gamma mode smoothly raises the lightmap brightness to the configured value by
 * overriding the brightness option in LightmapRenderStateExtractor; NightVision mode
 * keeps the NIGHT_VISION effect applied while enabled.
 */
public final class FullBright {
    private static final int MIN_BRIGHTNESS = 1;
    private static final int MAX_BRIGHTNESS = 15;
    private static final int DEFAULT_BRIGHTNESS = 15;
    private static final float GAMMA_STEP = 0.1F;
    private static final int NIGHT_VISION_DURATION = 1337;

    private static final BooleanSetting ENABLED =
            new BooleanSetting.Builder()
                    .name("fullbright.enabled")
                    .defaultValue(false)
                    .build();
    private static final ModeSetting<Mode> MODE =
            new ModeSetting.Builder<Mode>()
                    .name("fullbright.mode")
                    .defaultValue(Mode.GAMMA)
                    .option(Mode.GAMMA, "gamma")
                    .option(Mode.NIGHT_VISION, "nightvision", "night_vision")
                    .build();
    private static final IntSetting BRIGHTNESS =
            new IntSetting.Builder()
                    .name("fullbright.brightness")
                    .defaultValue(DEFAULT_BRIGHTNESS)
                    .min(MIN_BRIGHTNESS)
                    .max(MAX_BRIGHTNESS)
                    .build();

    private static float gamma = 0.0F;

    private FullBright() {
    }

    public static void init() {
        EventBus.TICK.register("FullBright.tick", event -> {
            Minecraft client = event.client();
            tick(client);
        });
    }

    private static void tick(Minecraft client) {
        if (!ENABLED.get() || client.player == null) {
            return;
        }

        if (isNightVisionMode()) {
            client.player.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, NIGHT_VISION_DURATION));
        } else if (gamma < BRIGHTNESS.get()) {
            if (gamma == 0.0F) {
                gamma = client.options.gamma().get().floatValue();
            }
            gamma = Math.min(gamma + GAMMA_STEP, BRIGHTNESS.get());
        }
    }

    /**
     * Called from the LightmapRenderStateExtractor hook to replace vanilla brightness
     * option while gamma mode is active.
     */
    public static float overrideBrightness(float original) {
        if (ENABLED.get() && !isNightVisionMode()) {
            return gamma;
        }

        return original;
    }

    public static boolean isEnabled() {
        return ENABLED.get();
    }

    public static String modeText() {
        return MODE.serialized();
    }

    public static int brightness() {
        return BRIGHTNESS.get();
    }

    public static String statusText() {
        return ENABLED.get() ? "enabled" : "disabled";
    }

    private static boolean isNightVisionMode() {
        return MODE.get() == Mode.NIGHT_VISION;
    }

    private static int status(Minecraft client) {
        ClientChat.send(client, "FullBright: " + statusText()
                + ", mode: " + modeText()
                + ", brightness: " + BRIGHTNESS.get()
                + ". Usage: .moons fullbright <enable|disable|mode gamma|nightvision|brightness 1-15>");
        return 1;
    }

    public static int setEnabled(Minecraft client, boolean newEnabled) {
        ENABLED.set(newEnabled);

        if (ENABLED.get()) {
            if (isNightVisionMode()) {
                if (client.player != null) {
                    client.player.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, NIGHT_VISION_DURATION));
                }
            } else {
                gamma = client.options.gamma().get().floatValue();
            }
        } else {
            if (isNightVisionMode() && client.player != null) {
                client.player.removeEffect(MobEffects.NIGHT_VISION);
            }
            gamma = 0.0F;
        }

        return status(client);
    }

    public static int setMode(Minecraft client, String newMode) {
        MODE.deserialize(newMode);
        boolean nightVision = isNightVisionMode();

        if (ENABLED.get()) {
            if (nightVision) {
                if (client.player != null) {
                    client.player.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, NIGHT_VISION_DURATION));
                }
            } else {
                if (client.player != null) {
                    client.player.removeEffect(MobEffects.NIGHT_VISION);
                }
                gamma = client.options.gamma().get().floatValue();
            }
        }

        return status(client);
    }

    public static List<String> modeOptions() { return MODE.optionIds(); }
    public static boolean gammaMode() { return MODE.get() == Mode.GAMMA; }

    public static int setBrightness(Minecraft client, int newBrightness) {
        BRIGHTNESS.set(newBrightness);
        return status(client);
    }

    private enum Mode { GAMMA, NIGHT_VISION }
}
