package com.blanoir.tempos.client.module.framework;

import static com.blanoir.tempos.client.module.framework.SettingFactory.bool;

import com.blanoir.tempos.client.config.Settings;
import com.blanoir.tempos.client.module.framework.ModuleModel.Setting;

import java.util.ArrayList;
import java.util.List;

/** Adds the two ray policy options after a placement feature's own settings. */
public final class PlacementSettings {
    private PlacementSettings() {}

    public static Setting[] append(String id, Setting... settings) {
        List<Setting> values = new ArrayList<>(List.of(settings));
        for (String option : List.of("throughentity", "throughblocks")) {
            values.add(
                    bool(
                            option,
                            option.equals("throughentity") ? "Through Entity" : "Through Blocks",
                            id + "." + option,
                            false,
                            (client, value) -> {
                                Settings.setBoolean(id + "." + option, value);
                                return 1;
                            }));
        }
        return values.toArray(Setting[]::new);
    }
}
