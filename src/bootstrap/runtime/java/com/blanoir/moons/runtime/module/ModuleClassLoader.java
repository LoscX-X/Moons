package com.blanoir.moons.runtime.module;

import java.net.URL;
import java.net.URLClassLoader;

/** Child-first for feature code, parent-first for shared API, JDK and game types. */
final class ModuleClassLoader extends URLClassLoader {
    private static final String[] PARENT_FIRST = {
        "java.",
        "javax.",
        "jdk.",
        "sun.",
        "com.blanoir.moons.api.",
        "com.blanoir.moons.runtime.",
        "net.minecraft.",
        "com.mojang."
    };

    static {
        ClassLoader.registerAsParallelCapable();
    }

    ModuleClassLoader(URL jar, ClassLoader parent) {
        super(new URL[] {jar}, parent);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> loaded = findLoadedClass(name);
            if (loaded == null && !parentFirst(name)) {
                try {
                    loaded = findClass(name);
                } catch (ClassNotFoundException ignored) {
                }
            }
            if (loaded == null) loaded = super.loadClass(name, false);
            if (resolve) resolveClass(loaded);
            return loaded;
        }
    }

    private static boolean parentFirst(String name) {
        for (String prefix : PARENT_FIRST) {
            if (name.startsWith(prefix)) return true;
        }
        return false;
    }
}
