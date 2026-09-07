package com.blanoir.moons.client.config.feature;

import com.blanoir.moons.client.config.settings.DoubleSetting;
import com.blanoir.moons.client.config.settings.ModeSetting;
import com.blanoir.moons.client.management.combat.CriticalHitTracker;

import net.minecraft.client.Minecraft;

import java.util.List;
import java.util.Locale;

/** Shared TargetInfo/Nametags hit-estimate policy. */
public final class HitEstimateSettings {
    private static final ModeSetting<EstimateMode> MODE =
            new ModeSetting.Builder<EstimateMode>()
                    .name("hitestimate.mode")
                    .defaultValue(EstimateMode.NORMAL)
                    .option(EstimateMode.NORMAL, "normal_estimate")
                    .option(EstimateMode.CRITICAL, "critical_estimate")
                    .build();
    private static final ModeSetting<CriticalPercentSource> CRITICAL_SOURCE =
            new ModeSetting.Builder<CriticalPercentSource>()
                    .name("hitestimate.criticalPercentSource")
                    .defaultValue(CriticalPercentSource.RECORDED)
                    .option(CriticalPercentSource.RECORDED, "recorded")
                    .option(CriticalPercentSource.CUSTOM, "custom")
                    .build();
    private static final DoubleSetting CUSTOM_CRITICAL_PERCENT =
            new DoubleSetting.Builder()
                    .name("hitestimate.customCriticalPercent")
                    .defaultValue(50.0D)
                    .range(0.0D, 100.0D)
                    .build();

    private HitEstimateSettings() {}

    public static boolean criticalEstimate() {
        return MODE.get() == EstimateMode.CRITICAL;
    }

    public static boolean recordedSource() {
        return CRITICAL_SOURCE.get() == CriticalPercentSource.RECORDED;
    }

    public static String mode() {
        return MODE.serialized();
    }

    public static List<String> modeOptions() {
        return MODE.optionIds();
    }

    public static String criticalSource() {
        return CRITICAL_SOURCE.serialized();
    }

    public static List<String> criticalSourceOptions() {
        return CRITICAL_SOURCE.optionIds();
    }

    public static double customCriticalPercent() {
        return CUSTOM_CRITICAL_PERCENT.get();
    }

    /** Probability used by the expectation model, in [0, 1]. */
    public static double criticalRate() {
        if (!criticalEstimate()) return 0.0D;
        double percent =
                recordedSource()
                        ? CriticalHitTracker.recordedPercentOr(CUSTOM_CRITICAL_PERCENT.get())
                        : CUSTOM_CRITICAL_PERCENT.get();
        return Math.max(0.0D, Math.min(1.0D, percent / 100.0D));
    }

    public static String statusText() {
        if (!criticalEstimate()) return "Normal estimate";
        if (!recordedSource()) {
            return String.format(
                    Locale.ROOT, "Critical %.0f%% custom", CUSTOM_CRITICAL_PERCENT.get());
        }
        return String.format(
                Locale.ROOT,
                "Critical %.0f%% recorded (%d)",
                CriticalHitTracker.recordedPercentOr(CUSTOM_CRITICAL_PERCENT.get()),
                CriticalHitTracker.samples());
    }

    public static int setMode(Minecraft ignoredClient, String mode) {
        MODE.deserialize(mode);
        return 1;
    }

    public static int setCriticalSource(Minecraft ignoredClient, String source) {
        CRITICAL_SOURCE.deserialize(source);
        return 1;
    }

    public static int setCustomCriticalPercent(Minecraft ignoredClient, double percent) {
        CUSTOM_CRITICAL_PERCENT.set(percent);
        return 1;
    }

    private enum EstimateMode {
        NORMAL,
        CRITICAL
    }

    private enum CriticalPercentSource {
        RECORDED,
        CUSTOM
    }
}
