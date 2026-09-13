package com.blanoir.moons.client.module.impl.render;

import com.blanoir.moons.client.config.settings.BooleanSetting;
import com.blanoir.moons.client.module.framework.ModuleRegistry;

import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffects;

/** Filters effect rendering; never removes an effect or changes its remaining duration. */
public final class AntiDebuff {
    private static final BooleanSetting ENABLED = flag("enabled", false);
    private static final BooleanSetting BLINDNESS = flag("blindness", true);
    private static final BooleanSetting NAUSEA = flag("nausea", true);
    private static final BooleanSetting DARKNESS = flag("darkness", true);

    private AntiDebuff() {}

    public static boolean enabled() {
        return ENABLED.get();
    }

    public static int setEnabled(Minecraft client, boolean value) {
        ENABLED.set(value);
        return 1;
    }

    public static boolean suppress(Holder<MobEffect> effect) {
        return enabled()
                && (effect.equals(MobEffects.BLINDNESS) && BLINDNESS.get()
                        || effect.equals(MobEffects.NAUSEA) && NAUSEA.get()
                        || effect.equals(MobEffects.DARKNESS) && DARKNESS.get());
    }

    public static ModuleRegistry.Setting[] settings() {
        return new ModuleRegistry.Setting[] {
            BLINDNESS.describe(
                    "blindness",
                    "Blindness",
                    (client, value) -> {
                        BLINDNESS.set(value);
                        return 1;
                    }),
            NAUSEA.describe(
                    "nausea",
                    "Nausea",
                    (client, value) -> {
                        NAUSEA.set(value);
                        return 1;
                    }),
            DARKNESS.describe(
                    "darkness",
                    "Darkness",
                    (client, value) -> {
                        DARKNESS.set(value);
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
}
