package com.blanoir.moons.runtime.module;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

record ModuleDescriptor(
        String id,
        String version,
        String entrypoint,
        String minecraft,
        int api,
        java.util.List<String> libraries) {
    private static final String DESCRIPTOR = "META-INF/moons-module.properties";

    static ModuleDescriptor read(java.nio.file.Path jarPath) throws IOException {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            JarEntry entry = jar.getJarEntry(DESCRIPTOR);
            if (entry == null) throw new IOException("Missing " + DESCRIPTOR + " in " + jarPath);
            Properties properties = new Properties();
            try (InputStream input = jar.getInputStream(entry)) {
                properties.load(input);
            }
            String id = required(properties, "id");
            if (!id.matches("[a-z0-9][a-z0-9._-]{0,63}")) {
                throw new IOException("Invalid module id: " + id);
            }
            int api;
            try {
                api = Integer.parseInt(required(properties, "api"));
            } catch (NumberFormatException failure) {
                throw new IOException("Invalid module API version", failure);
            }
            return new ModuleDescriptor(
                    id,
                    required(properties, "version"),
                    required(properties, "entrypoint"),
                    required(properties, "minecraft"),
                    api,
                    parseLibraries(properties.getProperty("libraries", "")));
        }
    }

    private static java.util.List<String> parseLibraries(String value) throws IOException {
        var libraries = new java.util.LinkedHashSet<String>();
        for (String item : value.split(",")) {
            String name = item.trim();
            if (name.isEmpty()) continue;
            // File names, not paths or URLs: dependencies live directly in MOONS_HOME/libraries.
            if (!name.matches("[A-Za-z0-9][A-Za-z0-9._-]*\\.jar") || name.contains("..")) {
                throw new IOException("Invalid module library: " + name);
            }
            libraries.add(name);
        }
        return java.util.List.copyOf(libraries);
    }

    boolean supportsMinecraft(String version) {
        for (String candidate : minecraft.split(",")) {
            if (candidate.trim().equals(version)) return true;
        }
        return false;
    }

    private static String required(Properties properties, String name) throws IOException {
        String value = properties.getProperty(name);
        if (value == null || value.isBlank())
            throw new IOException("Missing module property: " + name);
        return value.trim();
    }
}
