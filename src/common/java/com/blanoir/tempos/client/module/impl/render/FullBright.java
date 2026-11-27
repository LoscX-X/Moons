package com.blanoir.tempos.client.module.impl.render;


import com.blanoir.tempos.client.chat.ClientChat;
import com.blanoir.tempos.client.config.settings.BooleanSetting;
import com.blanoir.tempos.client.config.settings.IntSetting;
import com.blanoir.tempos.client.config.settings.ModeSetting;
import com.blanoir.tempos.client.event.EventBus;
import com.blanoir.tempos.client.module.framework.ClientFeature;
import com.blanoir.tempos.client.module.framework.ModuleCategory;
import com.blanoir.tempos.client.module.framework.ModuleInfo;
import com.blanoir.tempos.client.module.framework.ModuleModel.Setting;

import net.minecraft.client.Minecraft;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;

/**
 * Full-brightness rendering control.
 *
 * Gamma mode smoothly raises the lightmap brightness to the configured value by
 * overriding the brightness option in LightmapRenderStateExtractor; NightVision mode
 * keeps the NIGHT_VISION effect applied while enabled.
 */
@ModuleInfo(
        category = ModuleCategory.RENDER,
        description = "Adjusts world brightness using the selected lighting mode.")
public final class FullBright extends ClientFeature {
    public static final FullBright INSTANCE = new FullBright();

    private static final int MIN_BRIGHTNESS = 1;
    private static final int MAX_BRIGHTNESS = 15;
    private static final int DEFAULT_BRIGHTNESS = 15;
    private static final float GAMMA_STEP = 0.1F;
    private static final int NIGHT_VISION_DURATION = 1337;

    private final BooleanSetting enabled =
            new BooleanSetting.Builder().name("fullbright.enabled").defaultValue(false).build();
    private final ModeSetting<Mode> mode =
            new ModeSetting.Builder<Mode>()
                    .name("fullbright.mode")
                    .defaultValue(Mode.GAMMA)
                    .option(Mode.GAMMA, "gamma")
                    .option(Mode.NIGHT_VISION, "nightvision")
                    .build();
    private final IntSetting brightness =
            new IntSetting.Builder()
                    .name("fullbright.brightness")
                    .defaultValue(DEFAULT_BRIGHTNESS)
                    .min(MIN_BRIGHTNESS)
                    .max(MAX_BRIGHTNESS)
                    .build();

    private float gamma = 0.0F;

    private FullBright() {
        super("fullbright", "FullBright");
    }

    @Override
    public void registerEvents() {
        EventBus.TICK.register(
                "FullBright.tick",
                event -> {
                    Minecraft client = event.client();
                    tick(client);
                });
    }

    private void tick(Minecraft client) {
        var currentPlayer = client == null ? null : client.player;
        if (!this.enabled.get() || client == null || currentPlayer == null) {
            return;
        }

        if (isNightVisionMode()) {
            currentPlayer.addEffect(
                    new MobEffectInstance(MobEffects.NIGHT_VISION, NIGHT_VISION_DURATION));
        } else if (this.gamma < this.brightness.get()) {
            if (this.gamma == 0.0F) {
                this.gamma = client.options.gamma().get().floatValue();
            }
            this.gamma = Math.min(this.gamma + GAMMA_STEP, this.brightness.get());
        }
    }

    /**
     * Called from the LightmapRenderStateExtractor hook to replace vanilla brightness
     * option while gamma mode is active.
     */
    public float overrideBrightness(float original) {
        if (this.enabled.get() && !isNightVisionMode()) {
            return this.gamma;
        }

        return original;
    }

    @Override
    public boolean isEnabled() {
        return this.enabled.get();
    }

    public String modeText() {
        return this.mode.serialized();
    }

    public int brightness() {
        return this.brightness.get();
    }

    public String statusText() {
        return this.enabled.get() ? "enabled" : "disabled";
    }

    private boolean isNightVisionMode() {
        return this.mode.get() == Mode.NIGHT_VISION;
    }

    private int status(Minecraft client) {
        ClientChat.send(
                client,
                "FullBright: "
                        + statusText()
                        + ", mode: "
                        + modeText()
                        + ", brightness: "
                        + this.brightness.get()
                        + ". Usage: .tempos fullbright <enable|disable|mode gamma|nightvision|brightness 1-15>");
        return 1;
    }

    @Override
    public int setEnabled(Minecraft client, boolean newEnabled) {
        var currentPlayer = client == null ? null : client.player;
        this.enabled.set(newEnabled);

        if (this.enabled.get()) {
            if (isNightVisionMode()) {
                if (currentPlayer != null) {
                    currentPlayer.addEffect(
                            new MobEffectInstance(MobEffects.NIGHT_VISION, NIGHT_VISION_DURATION));
                }
            } else {
                this.gamma = client == null ? 0.0F : client.options.gamma().get().floatValue();
            }
        } else {
            if (isNightVisionMode() && currentPlayer != null) {
                currentPlayer.removeEffect(MobEffects.NIGHT_VISION);
            }
            this.gamma = 0.0F;
        }

        return status(client);
    }

    public int setMode(Minecraft client, String newMode) {
        var currentPlayer = client == null ? null : client.player;
        this.mode.deserialize(newMode);
        boolean nightVision = isNightVisionMode();

        if (this.enabled.get()) {
            if (nightVision) {
                if (currentPlayer != null) {
                    currentPlayer.addEffect(
                            new MobEffectInstance(MobEffects.NIGHT_VISION, NIGHT_VISION_DURATION));
                }
            } else {
                if (currentPlayer != null) {
                    currentPlayer.removeEffect(MobEffects.NIGHT_VISION);
                }
                this.gamma = client == null ? 0.0F : client.options.gamma().get().floatValue();
            }
        }

        return status(client);
    }

    public boolean gammaMode() {
        return this.mode.get() == Mode.GAMMA;
    }

    public int setBrightness(Minecraft client, int newBrightness) {
        this.brightness.set(newBrightness);
        return status(client);
    }

    private enum Mode {
        GAMMA,
        NIGHT_VISION
    }

    @Override
    protected String statusTag() {
        return this.modeText();
    }

    @Override
    protected Setting[] defineSettings() {
        return new Setting[] {
            mode.describeStored("mode", "Mode", this::setMode),
            brightness
                    .describeStored("brightness", "Brightness", 1, this::setBrightness)
                    .visibleWhen(this::gammaMode)
        };
    }
}
