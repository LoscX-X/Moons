package com.blanoir.tempos.client.module.impl.combat.cushionaura;

import com.blanoir.tempos.client.config.settings.BooleanSetting;
import com.blanoir.tempos.client.config.settings.DoubleSetting;
import com.blanoir.tempos.client.config.settings.IntSetting;
import com.blanoir.tempos.client.module.framework.ModuleModel;

/** Cushion timing is measured in client ticks; network acknowledgement has its own timeout. */
final class CushionSettings {
    static final IntSetting INTERVAL = integer("intervalTicks", 1, 0, 300);
    static final IntSetting MOUNT_DELAY = integer("mountDelayTicks", 0, 0, 40);
    static final IntSetting ATTACK_DELAY = integer("attackDelayTicks", 0, 0, 40);
    static final IntSetting PLACE_TURN = integer("placeTurnTicks", 0, 0, 20);
    static final IntSetting MOUNT_TURN = integer("mountTurnTicks", 0, 0, 20);
    static final IntSetting RETRY = integer("retryTicks", 1, 0, 100);
    static final IntSetting TIMEOUT = integer("timeoutMs", 2500, 250, 10000);
    static final DoubleSetting RADIUS = decimal("radius", 2, .75, 3);
    static final DoubleSetting TARGET_DISTANCE = decimal("minTargetDistance", .75, .5, 3);
    static final DoubleSetting SEAT_DISTANCE = decimal("minSeatDistance", .75, .5, 4);
    static final BooleanSetting ATTACK_BEFORE_MOVE =
            new BooleanSetting.Builder()
                    .name("cushionaura.attackBeforeMove")
                    .defaultValue(true)
                    .build();

    private CushionSettings() {}

    static ModuleModel.Setting[] descriptors() {
        return new ModuleModel.Setting[] {
            describe(INTERVAL, "interval", "Seat change interval (ticks)"),
            describe(MOUNT_DELAY, "mount_delay", "Place to mount delay (ticks)"),
            describe(ATTACK_DELAY, "attack_delay", "Seated attack delay (ticks)"),
            describe(PLACE_TURN, "place_turn", "Placement turn (ticks, 0 = instant)"),
            describe(MOUNT_TURN, "mount_turn", "Mount turn (ticks, 0 = instant)"),
            describe(RETRY, "retry", "Search / retry delay (ticks)"),
            TIMEOUT.describe(
                    "timeout",
                    "Confirmation timeout (ms)",
                    50,
                    (client, value) -> {
                        TIMEOUT.set(value);
                        return 1;
                    }),
            ATTACK_BEFORE_MOVE.describe(
                    "attack_before_move",
                    "Attack once before changing seat",
                    (client, value) -> {
                        ATTACK_BEFORE_MOVE.set(value);
                        return 1;
                    }),
            describe(RADIUS, "radius", "Maximum distance from target"),
            describe(TARGET_DISTANCE, "target_distance", "Minimum distance from target"),
            describe(SEAT_DISTANCE, "seat_distance", "Minimum seat change distance")
        };
    }

    private static ModuleModel.Setting describe(IntSetting setting, String id, String label) {
        return setting.describe(
                id,
                label,
                1,
                (client, value) -> {
                    setting.set(value);
                    return 1;
                });
    }

    private static ModuleModel.Setting describe(DoubleSetting setting, String id, String label) {
        return setting.describe(
                id,
                label,
                .05,
                (client, value) -> {
                    setting.set(value);
                    return 1;
                });
    }

    private static IntSetting integer(String name, int value, int min, int max) {
        return new IntSetting.Builder()
                .name("cushionaura." + name)
                .defaultValue(value)
                .range(min, max)
                .build();
    }

    private static DoubleSetting decimal(String name, double value, double min, double max) {
        return new DoubleSetting.Builder()
                .name("cushionaura." + name)
                .defaultValue(value)
                .range(min, max)
                .build();
    }
}
