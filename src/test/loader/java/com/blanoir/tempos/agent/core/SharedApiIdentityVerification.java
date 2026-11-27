package com.blanoir.tempos.agent.core;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

/** Run with tempos-api.jar on the bootstrap path, as in the production native loader. */
public final class SharedApiIdentityVerification {
    public static void main(String[] args) throws Exception {
        String name = "com.blanoir.tempos.api.Branding";
        Class<?> shared = Class.forName(name, false, null);
        byte[] bytes;
        try (var input = shared.getResourceAsStream("Branding.class")) {
            bytes = input.readAllBytes();
        }
        ClassLoader game = new ClassLoader(null) {
            @Override protected Class<?> findClass(String requested) throws ClassNotFoundException {
                if (requested.equals(name)) return defineClass(requested, bytes, 0, bytes.length);
                throw new ClassNotFoundException(requested);
            }
            @Override protected Class<?> loadClass(String requested, boolean resolve) throws ClassNotFoundException {
                if (!requested.equals(name)) return super.loadClass(requested, resolve);
                Class<?> found = findLoadedClass(requested);
                return found == null ? findClass(requested) : found;
            }
        };
        require(game.loadClass(name) != shared, "Game fixture did not define a conflicting API");
        Class<?> bridgeType = Class.forName(RuntimeLoader.class.getName() + "$BridgeParentClassLoader");
        var bridgeConstructor = bridgeType.getDeclaredConstructor(ClassLoader.class, ClassLoader.class);
        bridgeConstructor.setAccessible(true);
        ClassLoader bridge = (ClassLoader) bridgeConstructor.newInstance(game, RuntimeLoader.class.getClassLoader());
        require(bridge.loadClass(name) == shared, "Game loader replaced the shared Tempos API");

        Path jar = Files.createTempFile(Path.of(args[0]), "duplicate-api-", ".jar");
        try {
            try (var output = new JarOutputStream(Files.newOutputStream(jar))) {
                output.putNextEntry(new JarEntry(name.replace('.', '/') + ".class"));
                output.write(bytes);
                output.closeEntry();
            }
            Class<?> moduleType = Class.forName("com.blanoir.tempos.runtime.module.ModuleClassLoader");
            var moduleConstructor = moduleType.getDeclaredConstructor(URL[].class, ClassLoader.class);
            moduleConstructor.setAccessible(true);
            try (var module = (java.net.URLClassLoader) moduleConstructor.newInstance(
                    new URL[] {jar.toUri().toURL()}, SharedApiIdentityVerification.class.getClassLoader())) {
                require(module.loadClass(name) == shared, "Feature module replaced the shared Tempos API");
            }
            System.out.println("SHARED_API_IDENTITY_VERIFIED tempos=bootstrap game-conflict=ignored module-conflict=ignored");
        } finally {
            Files.deleteIfExists(jar);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
