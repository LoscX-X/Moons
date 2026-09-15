package com.blanoir.moons.features.catalog;

import static com.blanoir.moons.client.module.framework.ModuleRegistry.*;

import com.blanoir.moons.client.config.Settings;
import com.blanoir.moons.client.module.framework.ModuleRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

final class PlacementOptions {
    private PlacementOptions() {}

    static ModuleRegistry.Module module(
            String id,
            String name,
            String category,
            BooleanSupplier enabled,
            Toggle toggle,
            Supplier<String> tag,
            Setting... settings) {
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
        return ModuleRegistry.module(
                id, name, category, enabled, toggle, tag, values.toArray(Setting[]::new));
    }
}
