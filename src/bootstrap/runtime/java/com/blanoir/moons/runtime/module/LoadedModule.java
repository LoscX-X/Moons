package com.blanoir.moons.runtime.module;

import com.blanoir.moons.api.MoonsModule;
import com.blanoir.moons.runtime.lifecycle.DefaultResourceScope;

import java.lang.ref.WeakReference;
import java.nio.file.Path;

final class LoadedModule {
    final ModuleDescriptor descriptor;
    final Path source;
    final Path cachedJar;
    final java.util.List<Path> cachedLibraries;
    final ModuleClassLoader classLoader;
    final WeakReference<ClassLoader> leakReference;
    final DefaultResourceScope resources;
    final MoonsModule instance;
    boolean enabled;

    LoadedModule(
            ModuleDescriptor descriptor,
            Path source,
            Path cachedJar,
            java.util.List<Path> cachedLibraries,
            ModuleClassLoader classLoader,
            DefaultResourceScope resources,
            MoonsModule instance) {
        this.descriptor = descriptor;
        this.source = source;
        this.cachedJar = cachedJar;
        this.cachedLibraries = java.util.List.copyOf(cachedLibraries);
        this.classLoader = classLoader;
        this.leakReference = new WeakReference<>(classLoader);
        this.resources = resources;
        this.instance = instance;
    }
}
