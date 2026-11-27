package com.blanoir.tempos.client.ui.widget;

import com.blanoir.tempos.client.module.framework.ModuleModel.Module;
import java.util.WeakHashMap;
import java.util.function.Supplier;

/** Optional, read-only detail view registered by a particular module descriptor. */
public abstract class ModulePreview extends Widget {
    private static final WeakHashMap<Module, Supplier<? extends ModulePreview>> factories = new WeakHashMap<>();

    public static synchronized Module attach(Module module, Supplier<? extends ModulePreview> factory) {
        factories.put(module,factory);
        return module;
    }
    public static synchronized Supplier<? extends ModulePreview> factory(Module module) {
        return factories.get(module);
    }
    public abstract void refreshOnOpen();
}
