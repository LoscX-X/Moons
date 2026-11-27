package com.blanoir.tempos.client.ui.pages;

import com.blanoir.tempos.api.ModuleServices;
import com.blanoir.tempos.api.YsmSelector;
import com.blanoir.tempos.api.YsmStudio;
import com.blanoir.tempos.client.config.Settings;
import com.blanoir.tempos.client.event.EventBus;
import java.lang.ref.WeakReference;
import java.nio.file.Path;
import java.nio.file.Files;

/** Resolve the optional service per operation so an open page does not pin a module classloader. */
public final class YsmSelectorBinding {
    private static volatile ModuleServices services;
    private static WeakReference<YsmSelector> configured = new WeakReference<>(null);
    private static String configuredDirectory;
    private static final String DIRECTORY_SETTING = "ysm.scan.directory";

    private YsmSelectorBinding() {}

    public static AutoCloseable bind(ModuleServices registry) {
        services = registry;
        configured = new WeakReference<>(null);
        configuredDirectory = null;
        var subscription = EventBus.TICK_END.subscribe(ignored -> synchronizeDirectory());
        return () -> {
            subscription.close();
            if (services == registry) {
                services = null;
                configured.clear();
                configuredDirectory = null;
            }
        };
    }

    public static YsmSelector.Snapshot snapshot() {
        ModuleServices registry = services;
        YsmSelector selector = registry == null ? null : registry.findOrNull(YsmSelector.class);
        if (selector == null) return null;
        synchronizeDirectory(selector);
        return selector.snapshot();
    }

    public static void select(String id) {
        service().select(id);
    }

    public static void refresh() {
        service().refresh();
    }

    public static void directory(String path) {
        String value = path.trim();
        if (!value.isEmpty()) {
            Path folder = Path.of(value).toAbsolutePath().normalize();
            if (!Files.isDirectory(folder) || !Files.isReadable(folder))
                throw new IllegalArgumentException("Choose an existing, readable model folder.");
            value = folder.toString();
        }
        YsmSelector selector = service();
        selector.directory(value);
        Settings.setString(DIRECTORY_SETTING, value);
        configured = new WeakReference<>(selector);
        configuredDirectory = value;
    }

    private static void synchronizeDirectory() {
        ModuleServices registry = services;
        YsmSelector selector = registry == null ? null : registry.findOrNull(YsmSelector.class);
        if (selector != null) synchronizeDirectory(selector);
    }

    private static void synchronizeDirectory(YsmSelector selector) {
        String directory = Settings.getString(DIRECTORY_SETTING, "");
        if (configured.get() == selector && directory.equals(configuredDirectory)) return;
        selector.directory(directory);
        configured = new WeakReference<>(selector);
        configuredDirectory = directory;
    }

    public static void reset() {
        service().reset();
    }

    private static YsmSelector service() {
        ModuleServices registry = services;
        if (registry == null) throw new IllegalStateException("YSM module is unavailable");
        YsmSelector selector = registry.find(YsmSelector.class)
                .orElseThrow(() -> new IllegalStateException("YSM module is unavailable"));
        synchronizeDirectory(selector);
        return selector;
    }

    public static YsmStudio.Snapshot studioSnapshot() {
        ModuleServices registry = services;
        YsmStudio studio = registry == null ? null : registry.findOrNull(YsmStudio.class);
        return studio == null ? null : studio.studioSnapshot();
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
