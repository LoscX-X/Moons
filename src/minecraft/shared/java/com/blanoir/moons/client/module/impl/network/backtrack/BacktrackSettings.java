package com.blanoir.moons.client.module.impl.network.backtrack;

import com.blanoir.moons.client.config.settings.ModeSetting;
import com.blanoir.moons.client.config.settings.SettingSpec;

import java.util.List;

/** One definition for persisted keys, defaults and bounds shared by policy and descriptors. */
public final class BacktrackSettings {
    public static final ModeSetting.Spec<BacktrackConfig.TargetMode> TARGET_MODE =
            new ModeSetting.Spec<>(
                    "backtrack.targetMode",
                    BacktrackConfig.TargetMode.ATTACK,
                    List.of(
                            new ModeSetting.Option<>(BacktrackConfig.TargetMode.ATTACK, "attack"),
                            new ModeSetting.Option<>(BacktrackConfig.TargetMode.RANGE, "range"),
                            new ModeSetting.Option<>(BacktrackConfig.TargetMode.INTENT, "intent")));
    public static final ModeSetting.Spec<BacktrackConfig.EspMode> ESP =
            new ModeSetting.Spec<>(
                    "backtrack.esp",
                    BacktrackConfig.EspMode.BOX,
                    List.of(
                            new ModeSetting.Option<>(BacktrackConfig.EspMode.BOX, "box"),
                            new ModeSetting.Option<>(BacktrackConfig.EspMode.MODEL, "model"),
                            new ModeSetting.Option<>(
                                    BacktrackConfig.EspMode.WIREFRAME, "wireframe"),
                            new ModeSetting.Option<>(BacktrackConfig.EspMode.NONE, "none")));
    public static final SettingSpec<Boolean> ENABLED = SettingSpec.bool("backtrack.enabled", false);
    public static final SettingSpec<Integer> DELAY_MIN =
            SettingSpec.integer("backtrack.delay.min", 50, 0, 1000);
    public static final SettingSpec<Integer> DELAY_MAX =
            SettingSpec.integer("backtrack.delay.max", 70, 0, 1000);
    public static final SettingSpec<Double> RANGE_MIN =
            SettingSpec.number("backtrack.range.min", 1, 0, 10);
    public static final SettingSpec<Double> RANGE_MAX =
            SettingSpec.number("backtrack.range.max", 4, 0, 10);
    public static final SettingSpec<Integer> LAST_ATTACK =
            SettingSpec.integer("backtrack.lastAttackTimeToWork", 1000, 0, 5000);
    public static final SettingSpec<Integer> TRACKING_BUFFER =
            SettingSpec.integer("backtrack.trackingBuffer", 150, 0, 2000);
    public static final SettingSpec<Double> CHANCE =
            SettingSpec.number("backtrack.chance", 100, 0, 100);
    public static final SettingSpec<Integer> NEXT_MIN =
            SettingSpec.integer("backtrack.nextBacktrackDelay.min", 100, 0, 2000);
    public static final SettingSpec<Integer> NEXT_MAX =
            SettingSpec.integer("backtrack.nextBacktrackDelay.max", 150, 0, 2000);
    public static final SettingSpec<Boolean> PAUSE_HURT =
            SettingSpec.bool("backtrack.pauseOnHurtTime.enabled", false);
    public static final SettingSpec<Integer> HURT_TIME =
            SettingSpec.integer("backtrack.pauseOnHurtTime.hurtTime", 3, 0, 10);
    public static final SettingSpec<Integer> QUEUE_LIMIT =
            SettingSpec.integer("backtrack.maxQueueSize", 64, 32, 1024);
    public static final SettingSpec<Double> PING_RATIO =
            SettingSpec.number("backtrack.pingRatio", 0, 0, 3);
    public static final SettingSpec<Boolean> ACTION_BAR =
            SettingSpec.bool("backtrack.actionbar", false);

    private BacktrackSettings() {}
}
