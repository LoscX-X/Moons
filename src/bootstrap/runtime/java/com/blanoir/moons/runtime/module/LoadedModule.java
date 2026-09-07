package com.blanoir.moons.runtime.module;

import com.blanoir.moons.api.MoonsModule;
import com.blanoir.moons.runtime.lifecycle.DefaultResourceScope;

import java.lang.ref.WeakReference;
import java.nio.file.Path;

final class LoadedModule {
    final ModuleDescriptor descriptor;
    final Path source;
    final Path cachedJar;
    final ModuleClassLoader classLoader;
    final WeakReference<ClassLoader> leakReference;
    final DefaultResourceScope resources;
    final MoonsModule instance;
    boolean enabled;

    LoadedModule(
            ModuleDescriptor descriptor,
            Path source,
            Path cachedJar,
            ModuleClassLoader classLoader,
            DefaultResourceScope resources,
            MoonsModule instance) {
        this.descriptor = descriptor;
        this.source = source;
        this.cachedJar = cachedJar;
        this.classLoader = classLoader;
        this.leakReference = new WeakReference<>(classLoader);
        this.resources = resources;
        this.instance = instance;
    }
}
