package com.blanoir.moons.client.ui.clickgui;

import com.blanoir.moons.api.ModuleServices;
import com.blanoir.moons.api.YsmSelector;
import com.blanoir.moons.api.YsmStudio;

/** Resolve the optional service per operation so an open page does not pin a module classloader. */
public final class YsmSelectorHost {
    private static volatile ModuleServices services;

    private YsmSelectorHost() {}

    public static AutoCloseable bind(ModuleServices registry) {
        services = registry;
        return () -> {
            if (services == registry) services = null;
        };
    }

    public static YsmSelector.Snapshot snapshot() {
        ModuleServices registry = services;
        return registry == null
                ? null
                : registry.find(YsmSelector.class).map(YsmSelector::snapshot).orElse(null);
    }

    public static void select(String id) {
        service().select(id);
    }

    public static void refresh() {
        service().refresh();
    }

    public static void reset() {
        service().reset();
    }

    private static YsmSelector service() {
        ModuleServices registry = services;
        if (registry == null) throw new IllegalStateException("YSM module is unavailable");
        return registry.find(YsmSelector.class)
                .orElseThrow(() -> new IllegalStateException("YSM module is unavailable"));
    }

    public static YsmStudio.Snapshot studioSnapshot() {
        ModuleServices registry = services;
        return registry == null
                ? null
                : registry.find(YsmStudio.class).map(YsmStudio::studioSnapshot).orElse(null);
    }

    private static YsmStudio studio() {
        ModuleServices registry = services;
        if (registry == null) throw new IllegalStateException("YSM module is unavailable");
        return registry.find(YsmStudio.class)
                .orElseThrow(() -> new IllegalStateException("YSM studio is unavailable"));
    }

    public static void parameter(String id, double value) {
        studio().setParameter(id, value);
    }

    public static void play(String name) {
        studio().play(name);
    }

    public static void playback(double speed, boolean paused) {
        studio().playback(speed, paused);
    }

    public static void seek(double seconds) {
        studio().seek(seconds);
    }

    public static void texture(String name) {
        studio().texture(name);
    }

    public static void evaluate(String expression) {
        studio().evaluate(expression);
    }

    public static void resetParameters() {
        studio().resetParameters();
    }

    public static void pose(String bone, YsmStudio.BonePose pose) {
        studio().pose(bone, pose);
    }

    public static void resetPose() {
        studio().resetPose();
    }
}
