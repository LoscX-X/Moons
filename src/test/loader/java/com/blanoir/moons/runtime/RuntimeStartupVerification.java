package com.blanoir.moons.runtime;

import com.blanoir.moons.api.LoadMode;
import com.blanoir.moons.runtime.lifecycle.DefaultResourceScope;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import javax.tools.ToolProvider;

/** Actual core module loading, first tick, failed initialization and reverse scope cleanup. */
public final class RuntimeStartupVerification {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory(Path.of(args[0]), "startup-");
        Path source = root.resolve("fixture/Core.java");
        Files.createDirectories(source.getParent());
        Files.writeString(
                source,
                """
            package fixture;
            import com.blanoir.moons.api.*;
            import com.blanoir.moons.runtime.RuntimeEvents;
            public final class Core implements MoonsModule {
                private boolean optional;
                public void load(ModuleContext context) {
                    optional = context.moduleId().equals("optional");
                    context.resources().own(context.service(RuntimeEvents.class).clientTick().subscribe(event -> {}));
                    context.resources().own(() -> System.setProperty("fixture.cleaned", "yes"));
                    if (Boolean.getBoolean("fixture.fail.load")) throw new IllegalStateException("core load failed");
                }
                public void enable() {
                    if (optional) throw new AssertionError("optional enable failed");
                    if (Boolean.getBoolean("fixture.fail.enable")) throw new IllegalStateException("core enable failed");
                }
                public void disable() { if (Boolean.getBoolean("fixture.fail.disable")) throw new AssertionError("disable failed"); }
                public void unload() { System.setProperty("fixture.unloaded", "yes"); }
            }
            """);
        Path classes = root.resolve("classes");
        Files.createDirectories(classes);
        int compiled =
                ToolProvider.getSystemJavaCompiler()
                        .run(
                                null,
                                null,
                                null,
                                "-encoding",
                                "UTF-8",
                                "-cp",
                                System.getProperty("java.class.path"),
                                "-d",
                                classes.toString(),
                                source.toString());
        require(compiled == 0, "Startup fixture compilation failed");
        Path core = root.resolve("core.jar");
        try (var jar = new JarOutputStream(Files.newOutputStream(core))) {
            jar.putNextEntry(new JarEntry("fixture/Core.class"));
            jar.write(Files.readAllBytes(classes.resolve("fixture/Core.class")));
            jar.closeEntry();
            jar.putNextEntry(new JarEntry("META-INF/moons-module.properties"));
            jar.write(
                    "id=core-features\nversion=1\napi=1\nminecraft=26.1.2\nentrypoint=fixture.Core\nlibraries=\n"
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            jar.closeEntry();
        }
        Path outer = root.resolve("outer.jar");
        try (var jar = new JarOutputStream(Files.newOutputStream(outer))) {
            jar.putNextEntry(new JarEntry("META-INF/moons/modules/moons-core-features.jar"));
            jar.write(Files.readAllBytes(core));
            jar.closeEntry();
        }
        String fixture = System.getProperty("moons.fixture");
        System.setProperty("moons.fixture", "true");
        try {
            var bridge =
                    new DefaultRuntimeBridge(LoadMode.JVMTI, root.resolve("ok"), outer, "26.1.2");
            try {
                require(
                        bridge.startupStatus().startsWith("WAITING:"),
                        "Runtime reported READY before a tick");
                bridge.onClientTickStart(new Object());
                require(
                        bridge.startupStatus().startsWith("READY:"),
                        "Loaded core did not become ready");
                bridge.requestUnload();
                bridge.onClientTickEnd(new Object());
                require(
                        bridge.startupStatus().startsWith("FAILED:")
                                && bridge.events().clientTick().listenerCount() == 0,
                        "Stopped core remained ready or retained subscriptions");
            } finally {
                bridge.close();
            }
            Path optionalHome = root.resolve("optional");
            Files.createDirectories(optionalHome.resolve("modules"));
            try (var jar =
                    new JarOutputStream(
                            Files.newOutputStream(optionalHome.resolve("modules/optional.jar")))) {
                jar.putNextEntry(new JarEntry("fixture/Core.class"));
                jar.write(Files.readAllBytes(classes.resolve("fixture/Core.class")));
                jar.closeEntry();
                jar.putNextEntry(new JarEntry("META-INF/moons-module.properties"));
                jar.write(
                        "id=optional\nversion=1\napi=1\nminecraft=26.1.2\nentrypoint=fixture.Core\nlibraries=\n"
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                jar.closeEntry();
            }
            var degradedBridge =
                    new DefaultRuntimeBridge(LoadMode.JVMTI, optionalHome, outer, "26.1.2");
            try {
                degradedBridge.onClientTickStart(new Object());
                require(
                        degradedBridge.startupStatus().startsWith("READY:")
                                && degradedBridge
                                        .startupStatus()
                                        .contains("optional-module-failures=1"),
                        "Optional failure blocked core startup or lost its diagnostic");
                require(
                        degradedBridge.events().clientTick().listenerCount() == 1,
                        "Failed optional module retained subscription");
            } finally {
                degradedBridge.close();
            }
            System.setProperty("fixture.fail.disable", "true");
            System.clearProperty("fixture.cleaned");
            System.clearProperty("fixture.unloaded");
            var stoppingBridge =
                    new DefaultRuntimeBridge(
                            LoadMode.JVMTI, root.resolve("stop-failure"), outer, "26.1.2");
            try {
                stoppingBridge.onClientTickStart(new Object());
                stoppingBridge.close();
                require(
                        stoppingBridge.events().clientTick().listenerCount() == 0
                                && "yes".equals(System.getProperty("fixture.cleaned"))
                                && "yes".equals(System.getProperty("fixture.unloaded")),
                        "Disable Error stopped later resource/unload cleanup");
            } finally {
                stoppingBridge.close();
                System.clearProperty("fixture.fail.disable");
                System.clearProperty("fixture.unloaded");
            }
            for (String failure : List.of("load", "enable")) {
                System.setProperty("fixture.fail." + failure, "true");
                System.clearProperty("fixture.cleaned");
                var failedBridge =
                        new DefaultRuntimeBridge(
                                LoadMode.JVMTI, root.resolve(failure), outer, "26.1.2");
                try {
                    failedBridge.onClientTickStart(new Object());
                    require(
                            failedBridge.startupStatus().startsWith("FAILED:")
                                    && failedBridge
                                            .startupStatus()
                                            .contains("core " + failure + " failed"),
                            "Core failure reported ready");
                    require(
                            failedBridge.events().clientTick().listenerCount() == 0
                                    && "yes".equals(System.getProperty("fixture.cleaned")),
                            "Failed core retained resources");
                } finally {
                    failedBridge.close();
                    System.clearProperty("fixture.fail." + failure);
                }
            }
            verifyScope();
            System.out.println(
                    "MOONS_RUNTIME_STARTUP_VERIFIED waiting first-tick core-load-failure core-enable-failure unload cleanup-outside-lock");
        } finally {
            if (fixture == null) System.clearProperty("moons.fixture");
            else System.setProperty("moons.fixture", fixture);
            System.clearProperty("fixture.cleaned");
        }
    }

    private static void verifyScope() {
        var scope = new DefaultResourceScope();
        var sequence = new StringBuilder();
        scope.own(() -> sequence.append('a'));
        scope.own(
                () -> {
                    sequence.append('b');
                    throw new AssertionError("resource failure");
                });
        scope.own(
                () -> {
                    var observed = new java.util.concurrent.atomic.AtomicBoolean();
                    Thread reader = new Thread(() -> observed.set(scope.isClosed()));
                    reader.start();
                    reader.join(2000);
                    if (!observed.get())
                        throw new AssertionError("Resource was closed while holding scope monitor");
                    sequence.append('c');
                });
        try {
            scope.close();
            throw new AssertionError("Cleanup failure was hidden");
        } catch (RuntimeException expected) {
            require(
                    sequence.toString().equals("cba") && expected.getSuppressed().length == 1,
                    "Scope stopped early or changed release order");
        }
        scope.close();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
