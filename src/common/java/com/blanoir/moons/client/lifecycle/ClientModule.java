package com.blanoir.moons.client.lifecycle;

import com.blanoir.moons.api.ModuleContext;
import com.blanoir.moons.api.ModuleServices;
import com.blanoir.moons.api.MoonsModule;
import com.blanoir.moons.client.event.RuntimeEventAdapter;
import com.blanoir.moons.client.ui.clickgui.YsmSelectorBinding;
import com.blanoir.moons.runtime.RuntimeEvents;

/** Loads and owns the built-in client feature lifecycle. */
public final class ClientModule implements MoonsModule {
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
            if (started) ClientLifecycle.resume();
            else {
                context.resources()
                        .own(YsmSelectorBinding.bind(context.service(ModuleServices.class)));
                context.resources().own((AutoCloseable) ClientLifecycle::shutdown);
                ClientLifecycle.initialize();
                ClientLifecycle.start();
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
        if (adapter != null) {
            adapter.resetInputs();
            ClientLifecycle.suspend();
        }
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
