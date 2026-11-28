package com.blanoir.tempos.client.module.impl.combat.cushionaura;

import com.blanoir.tempos.client.config.settings.IntSetting;
import com.blanoir.tempos.client.module.framework.ModuleModel.Setting;

final class CusionRunSettings {
    static final IntSetting TURN = integer("turnTicks", 1, 1, 20);
    static final IntSetting PLACE_DELAY = integer("placeDelayTicks", 1, 1, 20);
    static final IntSetting TIMEOUT = integer("timeoutMs", 2500, 250, 10000);

    private CusionRunSettings() {}

    static Setting[] descriptors() {
        return new Setting[] {
            describe(TURN, "turn", "Placement / mount turn (ticks)", 1),
            describe(PLACE_DELAY, "place_delay", "Placement wait after turn (ticks)", 1),
            describe(TIMEOUT, "timeout", "Confirmation timeout (ms)", 50)
        };
    }

    private static Setting describe(IntSetting setting, String id, String label, int step) {
        return setting.describe(
                id,
                label,
                step,
                (client, value) -> {
                    setting.set(value);
                    return 1;
                });
    }

    private static IntSetting integer(String name, int value, int min, int max) {
        return new IntSetting.Builder()
                .name("cusionrun." + name)
                .defaultValue(value)
                .range(min, max)
                .build();
    }
}
