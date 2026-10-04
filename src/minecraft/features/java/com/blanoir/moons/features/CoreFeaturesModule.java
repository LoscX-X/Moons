package com.blanoir.moons.features;

import com.blanoir.moons.api.ModuleContext;
import com.blanoir.moons.api.ModuleServices;
import com.blanoir.moons.api.MoonsModule;
import com.blanoir.moons.client.management.task.ActivationGate;
import com.blanoir.moons.client.ui.clickgui.YsmSelectorHost;
import com.blanoir.moons.runtime.RuntimeEvents;

/** Loads and owns the built-in client feature lifecycle. */
public final class CoreFeaturesModule implements MoonsModule {
    private RuntimeEventAdapter adapter;
    private final ActivationGate activation = new ActivationGate();
    private boolean enabled;
    private boolean started;
    private ModuleContext context;

    @Override
    public void load(ModuleContext context) {
        this.context = context;
        RuntimeEvents events = context.service(RuntimeEvents.class);
        if (Boolean.getBoolean("moons.fixture")) {
            context.resources().own(events.clientTick().subscribe(activation.guard(event -> {})));
            return;
        }
        adapter = new RuntimeEventAdapter(events, activation);
        adapter.bind(context.resources());
    }

    @Override
    public void enable() {
        if (enabled) return;
        if (adapter != null) {
            if (started) FeatureBootstrap.resume();
            else {
                context.resources()
                        .own(YsmSelectorHost.bind(context.service(ModuleServices.class)));
                context.resources().own((AutoCloseable) FeatureBootstrap::shutdown);
                FeatureBootstrap.initialize();
                FeatureBootstrap.start();
            }
        }
        started = true;
        activation.activate();
        enabled = true;
    }

    @Override
    public void disable() {
        if (!enabled) return;
        activation.deactivate();
        enabled = false;
        if (adapter != null) FeatureBootstrap.suspend();
    }

    @Override
    public void unload() {
        if (enabled) {
            throw new IllegalStateException("Module must be disabled before unload");
        }
        adapter = null;
        context = null;
    }
}
