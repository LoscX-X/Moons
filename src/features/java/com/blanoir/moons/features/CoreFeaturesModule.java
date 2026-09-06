package com.blanoir.moons.features;

import com.blanoir.moons.api.ModuleContext;
import com.blanoir.moons.api.MoonsModule;
import com.blanoir.moons.runtime.RuntimeEvents;

/** Loads and owns the built-in client feature lifecycle. */
public final class CoreFeaturesModule implements MoonsModule {
    private RuntimeEventAdapter adapter;
    private boolean enabled;

    @Override
    public void load(ModuleContext context) {
        RuntimeEvents events = context.service(RuntimeEvents.class);
        if (Boolean.getBoolean("moons.fixture")) {
            context.resources().own(events.clientTick().subscribe(event -> { }));
            return;
        }
        adapter = new RuntimeEventAdapter(events);
        adapter.bind(context.resources());
        FeatureBootstrap.initialize();
    }

    @Override
    public void enable() {
        enabled = true;
    }

    @Override
    public void disable() {
        enabled = false;
    }

    @Override
    public void unload() {
        if (enabled) {
            throw new IllegalStateException("Module must be disabled before unload");
        }
        if (adapter != null) {
            FeatureBootstrap.shutdown();
        }
        adapter = null;
    }
}
