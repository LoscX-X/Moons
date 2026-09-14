package com.blanoir.moons.agent.core;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;

/** Resolves frame merges through the game loader without initializing classes. */
final class LoaderAwareClassWriter extends ClassWriter {
    private final ClassLoader loader;

    LoaderAwareClassWriter(ClassReader reader, int flags, ClassLoader loader) {
        super(reader, flags);
        this.loader = loader;
    }

    @Override
    protected String getCommonSuperClass(String firstName, String secondName) {
        if (loader == null) return "java/lang/Object";
        try {
            Class<?> first = Class.forName(firstName.replace('/', '.'), false, loader);
            Class<?> second = Class.forName(secondName.replace('/', '.'), false, loader);
            if (first.isAssignableFrom(second)) return firstName;
            if (second.isAssignableFrom(first)) return secondName;
            if (first.isInterface() || second.isInterface()) return "java/lang/Object";
            do {
                first = first.getSuperclass();
            } while (first != null && !first.isAssignableFrom(second));
            return first == null ? "java/lang/Object" : first.getName().replace('.', '/');
        } catch (ClassNotFoundException | LinkageError unavailable) {
            // Object is a conservative verifier-safe merge when a host-generated
            // helper is not visible through the game loader.
            return "java/lang/Object";
        }
    }
}
