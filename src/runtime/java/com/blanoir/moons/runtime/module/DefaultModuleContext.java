package com.blanoir.moons.runtime.module;

import com.blanoir.moons.api.ModuleContext;
import com.blanoir.moons.api.ResourceScope;

import java.nio.file.Path;
import java.util.Map;

record DefaultModuleContext(
        String moduleId,
        Path dataDirectory,
        ResourceScope resources,
        Map<Class<?>, Object> services
) implements ModuleContext {
    @Override
    public <T> T service(Class<T> type) {
        Object service = services.get(type);
        if (service == null) throw new IllegalArgumentException("Unknown service: " + type.getName());
        return type.cast(service);
    }
}
