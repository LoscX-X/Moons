package com.blanoir.moons.runtime.module;

import com.blanoir.moons.api.ModuleContext;
import com.blanoir.moons.api.MoonsModule;
import com.blanoir.moons.api.ResourceScope;
import com.blanoir.moons.api.ScopedResources;
import com.blanoir.moons.runtime.lifecycle.DefaultResourceScope;

import java.lang.ref.WeakReference;
import java.nio.file.Path;
import java.util.Map;

/** Owns one module's context, enabled state and resource lifetime. */
final class LoadedModule implements ModuleContext, AutoCloseable {
    final ModuleDescriptor descriptor;
    final Path source;
    final Path cachedJar;
    final java.util.List<Path> cachedLibraries;
    final ModuleClassLoader classLoader;
    final WeakReference<ClassLoader> leakReference;
    final DefaultResourceScope resources;
    final MoonsModule instance;
    private final Path dataDirectory;
    private final Map<Class<?>, Object> services;
    boolean enabled;
    private boolean closed;

    LoadedModule(
            ModuleDescriptor descriptor,
            Path source,
            Path cachedJar,
            java.util.List<Path> cachedLibraries,
            ModuleClassLoader classLoader,
            DefaultResourceScope resources,
            MoonsModule instance,
            Path dataDirectory,
            Map<Class<?>, Object> services) {
        this.descriptor = descriptor;
        this.source = source;
        this.cachedJar = cachedJar;
        this.cachedLibraries = java.util.List.copyOf(cachedLibraries);
        this.classLoader = classLoader;
        this.leakReference = new WeakReference<>(classLoader);
        this.resources = resources;
        this.instance = instance;
        this.dataDirectory = dataDirectory;
        this.services = Map.copyOf(services);
    }

    void load() throws Exception {
        ScopedResources.run(resources, () -> instance.load(this));
    }

    void enable() throws Exception {
        if (closed) throw new IllegalStateException("Module is closed: " + moduleId());
        if (enabled) return;
        ScopedResources.run(resources, instance::enable);
        enabled = true;
    }

    void disable() throws Exception {
        if (!enabled) return;
        instance.disable();
        enabled = false;
    }

    @Override
    public String moduleId() {
        return descriptor.id();
    }

    @Override
    public Path dataDirectory() {
        return dataDirectory;
    }

    @Override
    public ResourceScope resources() {
        return resources;
    }

    @Override
    public <T> T service(Class<T> type) {
        Object service = services.get(type);
        if (service == null)
            throw new IllegalArgumentException("Unknown service: " + type.getName());
        return type.cast(service);
    }

    @Override
    public void close() throws Exception {
        if (closed) return;
        closed = true;
        Exception aggregate = null;
        try {
            disable();
        } catch (Exception failure) {
            aggregate = failure;
        }
        try {
            resources.close();
        } catch (Exception failure) {
            if (aggregate == null) aggregate = failure;
            else aggregate.addSuppressed(failure);
        }
        try {
            instance.unload();
        } catch (Exception failure) {
            if (aggregate == null) aggregate = failure;
            else aggregate.addSuppressed(failure);
        }
        try {
            classLoader.close();
        } catch (Exception failure) {
            if (aggregate == null) aggregate = failure;
            else aggregate.addSuppressed(failure);
        }
        if (aggregate != null) throw aggregate;
    }
}
