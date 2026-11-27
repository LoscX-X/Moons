package com.blanoir.tempos.client.module.impl.render;

import com.blanoir.tempos.client.config.settings.BooleanSetting;
import com.blanoir.tempos.client.module.framework.ClientFeature;
import com.blanoir.tempos.client.module.framework.ModuleCategory;
import com.blanoir.tempos.client.module.framework.ModuleInfo;
import com.blanoir.tempos.client.module.framework.ModuleModel;
import com.blanoir.tempos.client.module.framework.ModuleModel.Setting;

import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffects;

/** Filters effect rendering; never removes an effect or changes its remaining duration. */
@ModuleInfo(
        category = ModuleCategory.RENDER,
        description = "Reduces the visual effects of blindness, nausea, and darkness.")
public final class AntiDebuff extends ClientFeature {
    public static final AntiDebuff INSTANCE = new AntiDebuff();

    private final BooleanSetting enabled = flag("enabled", false);
    private final BooleanSetting blindness = flag("blindness", true);
    private final BooleanSetting nausea = flag("nausea", true);
    private final BooleanSetting darkness = flag("darkness", true);

    private AntiDebuff() {
        super("antidebuff", "AntiDebuff");
    }

    public boolean enabled() {
        return this.enabled.get();
    }

    @Override
    public int setEnabled(Minecraft client, boolean value) {
        this.enabled.set(value);
        return 1;
    }

    public boolean suppress(Holder<MobEffect> effect) {
        return enabled()
                && (effect.equals(MobEffects.BLINDNESS) && this.blindness.get()
                        || effect.equals(MobEffects.NAUSEA) && this.nausea.get()
                        || effect.equals(MobEffects.DARKNESS) && this.darkness.get());
    }

    public ModuleModel.Setting[] settings() {
        return new ModuleModel.Setting[] {
            this.blindness.describe(
                    "blindness",
                    "Blindness",
                    (client, value) -> {
                        this.blindness.set(value);
                        return 1;
                    }),
            this.nausea.describe(
                    "nausea",
                    "Nausea",
                    (client, value) -> {
                        this.nausea.set(value);
                        return 1;
                    }),
            this.darkness.describe(
                    "darkness",
                    "Darkness",
                    (client, value) -> {
                        this.darkness.set(value);
                        return 1;
                    })
        };
    }

    private static BooleanSetting flag(String name, boolean fallback) {
        return new BooleanSetting.Builder()
                .name("antidebuff." + name)
                .defaultValue(fallback)
                .build();
    }

    @Override
    public boolean isEnabled() {
        return this.enabled();
    }

    @Override
    protected Setting[] defineSettings() {
        return this.settings();
    }
}
