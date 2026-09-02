package com.blanoir.moons.agent.core;

import com.blanoir.moons.api.Branding;
import com.blanoir.moons.api.AgentMode;
import com.blanoir.moons.api.bridge.AgentBridge;
import com.blanoir.moons.api.bridge.RuntimeBridge;

import java.lang.instrument.Instrumentation;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Owns the one transformer and replaceable runtime inside the target JVM. */
public final class AgentController {
    private static MoonsTransformer transformer;
    private static RuntimeLoader runtime;

    private AgentController() { }

    public static synchronized void start(
            Instrumentation instrumentation,
            String modeName,
            Path outerJar,
            String arguments
    ) throws Exception {
        AgentMode mode = AgentMode.valueOf(modeName);
        Path home = AgentArguments.home(arguments, outerJar);
        System.setProperty("moons.home", home.toString());
        String minecraftVersion = MinecraftEnvironment.detectVersion(instrumentation);
        if (!minecraftVersion.equals("pending")
                && !minecraftVersion.equals("unknown")
                && !minecraftVersion.equals(VersionMappings.version())) {
            throw new IllegalStateException("Unsupported Minecraft version " + minecraftVersion
                    + "; expected " + VersionMappings.version());
        }

        RuntimeLoader nextRuntime = RuntimeLoader.start(
                instrumentation, outerJar, home, mode, VersionMappings.version());
        RuntimeBridge previousBridge = AgentBridge.install(nextRuntime.bridge());
        RuntimeLoader previousRuntime = runtime;
        runtime = nextRuntime;
        if (previousRuntime != null) {
            try {
                previousRuntime.close();
            } catch (Exception failure) {
                System.err.println(Branding.prefix() + " Previous runtime did not close cleanly: " + failure);
            }
        } else if (previousBridge != RuntimeBridge.NOOP) {
            previousBridge.close();
        }

        if (transformer == null) {
            transformer = new MoonsTransformer(VersionMappings.create());
            instrumentation.addTransformer(transformer, true);
        }
        int retransformed = retransformLoadedTargets(instrumentation, transformer);
        System.out.println(Branding.prefix() + " Agent active: mode=" + mode
                + ", minecraft=" + minecraftVersion
                + ", retransformed=" + retransformed
                + ", hooks=" + transformer.installedHooks()
                + ", unavailable=" + transformer.failedHooks());
    }

    private static int retransformLoadedTargets(
            Instrumentation instrumentation,
            MoonsTransformer transformer
    ) throws Exception {
        List<Class<?>> targets = new ArrayList<>();
        for (Class<?> loadedClass : instrumentation.getAllLoadedClasses()) {
            String internalName = loadedClass.getName().replace('.', '/');
            if (transformer.targets(internalName) && instrumentation.isModifiableClass(loadedClass)) {
                targets.add(loadedClass);
            }
        }
        if (!targets.isEmpty()) {
            instrumentation.retransformClasses(targets.toArray(Class<?>[]::new));
        }
        return targets.size();
    }
}
