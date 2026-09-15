package com.blanoir.moons.runtime.module;

import com.blanoir.moons.api.ModuleServices;
import com.blanoir.moons.api.YsmSelector;
import com.blanoir.moons.runtime.RuntimeEvents;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;

import javax.tools.ToolProvider;

/** Exercises real module classloaders and watcher replacement without a Minecraft process. */
public final class ModuleLibrariesVerification {
    public static void main(String[] arguments) throws Exception {
        verifyServices();
        Path root = Files.createTempDirectory("moons-module-libraries-");
        try {
            Path home = root.resolve("home");
            Files.createDirectories(home.resolve("libraries"));
            Files.createDirectories(home.resolve("modules"));
            Path library = home.resolve("libraries/fixture.jar");
            buildLibrary(root, library, "one");
            Path classes =
                    compile(
                            root,
                            "module",
                            Map.of(
                                    "fixture/Module.java",
                                            """
                    package fixture;
                    import com.blanoir.moons.api.*;
                    import com.blanoir.moons.runtime.RuntimeEvents;
                    public class Module implements MoonsModule {
                        private boolean enabled;
                        public void load(ModuleContext context) {
                            if (!context.moduleId().equals("fixture") || context.dataDirectory() == null)
                                throw new AssertionError("module context");
                            if (lib.Value.get().equals("broken")) context.resources().own(() -> {
                                throw new IllegalStateException("resource cleanup");
                            });
                            context.resources().own(context.service(RuntimeEvents.class).methodHook()
                                .subscribe(event -> { if (enabled) event.value(lib.Value.get()); }));
                        }
                        public void enable() { if (lib.Value.get().equals("broken")) throw new IllegalStateException("fixture"); enabled=true; }
                        public void disable() { enabled=false; }
                        public void unload() { if (lib.Value.get().equals("broken")) throw new IllegalStateException("unload cleanup"); }
                    }
                    """,
                                    "fixture/Empty.java",
                                            """
                    package fixture;
                    import com.blanoir.moons.api.*;
                    public class Empty implements MoonsModule {
                        public void load(ModuleContext context) {}
                        public void enable() {}
                        public void disable() {}
                        public void unload() {}
                    }
                    """),
                            library);
            Path module = home.resolve("modules/fixture.jar");
            jar(module, classes, descriptor("fixture", "fixture.Module", "fixture.jar"));
            Path otherVersion = home.resolve("modules/z-fixture-26.2.jar");
            jar(
                    otherVersion,
                    classes,
                    descriptor("fixture", "fixture.Module", "missing-26.2.jar")
                            .replace("26.1.2", "26.2"));
            Path builtin = root.resolve("builtin.jar");
            jar(builtin, classes, descriptor("core-features", "fixture.Empty", ""));
            Path outer = root.resolve("moons.jar");
            try (var jar = new JarOutputStream(Files.newOutputStream(outer))) {
                jar.putNextEntry(new JarEntry("META-INF/moons/modules/moons-core-features.jar"));
                Files.copy(builtin, jar);
                jar.closeEntry();
            }
            RuntimeEvents events = new RuntimeEvents();
            try (var manager = new ModuleManager(home, outer, events, "26.1.2")) {
                manager.start();
                check("one".equals(value(events)), "initial library");
                manager.replace(otherVersion);
                check(
                        "one".equals(value(events)),
                        "another version never replaces the active adapter");
                check(events.methodHook().listenerCount() == 1, "one initial listener");
                buildLibrary(root, library, "two");
                long deadline = System.nanoTime() + 5_000_000_000L;
                while (!"two".equals(value(events)) && System.nanoTime() < deadline) {
                    Thread.sleep(25);
                    manager.drainReloads();
                }
                check("two".equals(value(events)), "library watcher replaced unchanged module jar");
                check(events.methodHook().listenerCount() == 1, "old listener released");
                buildLibrary(root, library, "broken");
                try {
                    manager.replace(module);
                    throw new AssertionError("broken enable accepted");
                } catch (IllegalStateException expected) {
                    check(
                            expected.getMessage().equals("fixture"),
                            "original enable failure preserved");
                    check(expected.getSuppressed().length == 1, "cleanup failure attached");
                    check(
                            expected.getSuppressed()[0].getSuppressed().length == 2,
                            "resource and unload failures retained");
                }
                check("two".equals(value(events)), "failed candidate kept previous library");
                check(
                        events.methodHook().listenerCount() == 1,
                        "failed candidate released listener");
                Files.delete(library);
                try {
                    manager.replace(module);
                    throw new AssertionError("missing library accepted");
                } catch (NoSuchFileException expected) {
                }
                check(
                        "two".equals(value(events)),
                        "deleted source jar leaves cached active library usable");
                manager.unload("fixture");
                check(events.methodHook().listenerCount() == 0, "unload released listener");
            }
            Path invalid = root.resolve("invalid.jar");
            jar(invalid, classes, descriptor("fixture", "fixture.Module", "../escape.jar"));
            try {
                ModuleDescriptor.read(invalid);
                throw new AssertionError("library path traversal accepted");
            } catch (IOException expected) {
            }
            System.out.println(
                    "MOONS_MODULE_LIBRARIES_VERIFIED watcher=replace rollback=preserved unload=released services=scoped");
        } finally {
            try (var walk = Files.walk(root)) {
                for (Path path : walk.sorted(Comparator.reverseOrder()).toList())
                    Files.deleteIfExists(path);
            }
        }
    }

    private static void verifyServices() throws Exception {
        ModuleServices services = new ModuleServices();
        YsmSelector provider =
                new YsmSelector() {
                    public Snapshot snapshot() {
                        return new Snapshot("", List.of(), "", "", State.VANILLA, "");
                    }

                    public void select(String id) {}

                    public void refresh() {}

                    public void reset() {}
                };
        AutoCloseable old = services.register(YsmSelector.class, provider);
        check(services.find(YsmSelector.class).orElseThrow() == provider, "service identity");
        old.close();
        try (var current = services.register(YsmSelector.class, provider)) {
            old.close();
            check(
                    services.find(YsmSelector.class).orElseThrow() == provider,
                    "stale close cannot remove re-enabled provider");
        }
        check(services.find(YsmSelector.class).isEmpty(), "service released");
    }

    private static Object value(RuntimeEvents events) {
        var event = new RuntimeEvents.MethodHook("fixture", null, null, null);
        events.methodHook().publish(event);
        return event.value();
    }

    private static String descriptor(String id, String entrypoint, String libraries) {
        return "id="
                + id
                + "\nversion=1\napi=1\nminecraft=26.1.2\nentrypoint="
                + entrypoint
                + "\nlibraries="
                + libraries
                + "\n";
    }

    private static void buildLibrary(Path root, Path target, String value) throws IOException {
        Path classes =
                compile(
                        root,
                        "library-" + value,
                        Map.of(
                                "lib/Value.java",
                                "package lib; public class Value { public static String get() { return \""
                                        + value
                                        + "\"; } }"),
                        null);
        jar(target, classes, null);
    }

    private static Path compile(Path root, String name, Map<String, String> sources, Path library)
            throws IOException {
        Path directory = root.resolve(name);
        Path output = directory.resolve("classes");
        Files.createDirectories(output);
        List<String> args =
                new ArrayList<>(
                        List.of(
                                "-encoding",
                                "UTF-8",
                                "-classpath",
                                System.getProperty("java.class.path")
                                        + (library == null ? "" : File.pathSeparator + library),
                                "-d",
                                output.toString()));
        for (var source : sources.entrySet()) {
            Path path = directory.resolve(source.getKey());
            Files.createDirectories(path.getParent());
            Files.writeString(path, source.getValue());
            args.add(path.toString());
        }
        check(
                ToolProvider.getSystemJavaCompiler()
                                .run(null, null, null, args.toArray(String[]::new))
                        == 0,
                "fixture compilation");
        return output;
    }

    private static void jar(Path target, Path classes, String descriptor) throws IOException {
        try (var jar = new JarOutputStream(Files.newOutputStream(target));
                var walk = Files.walk(classes)) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                jar.putNextEntry(
                        new JarEntry(classes.relativize(file).toString().replace('\\', '/')));
                Files.copy(file, jar);
                jar.closeEntry();
            }
            if (descriptor != null) {
                jar.putNextEntry(new JarEntry("META-INF/moons-module.properties"));
                jar.write(descriptor.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                jar.closeEntry();
            }
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
