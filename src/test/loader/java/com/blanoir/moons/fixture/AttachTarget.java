package com.blanoir.moons.fixture;

import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;

/** Standalone no-Fabric JVM used to prove the Attach and retransform path. */
public final class AttachTarget {
    private AttachTarget() {}

    public static void main(String[] arguments) throws Exception {
        System.setProperty("moons.fixture", "true");
        URL fixtureJar = AttachTarget.class.getProtectionDomain().getCodeSource().getLocation();
        if (arguments.length > 0 && arguments[0].equals("--system-loader")) {
            runFixture(ClassLoader.getSystemClassLoader());
            return;
        }
        try (URLClassLoader gameLoader = new GenesisLikeClassLoader(fixtureJar)) {
            runFixture(gameLoader);
        }
    }

    private static void runFixture(ClassLoader gameLoader) throws Exception {
        Thread.currentThread().setContextClassLoader(gameLoader);
        Class<?> minecraftClass = Class.forName("net.minecraft.client.Minecraft", true, gameLoader);
        Object minecraft = minecraftClass.getConstructor().newInstance();
        Method tick = minecraftClass.getMethod("tick");
        Method ticks = minecraftClass.getMethod("ticks");
        System.out.println(
                "MOONS_FIXTURE_READY pid="
                        + ProcessHandle.current().pid()
                        + " gameLoader="
                        + gameLoader);
        System.out.flush();
        long deadline = System.nanoTime() + java.time.Duration.ofMinutes(2).toNanos();
        while (System.nanoTime() < deadline) {
            tick.invoke(minecraft);
            Thread.sleep(50L);
        }
        System.out.println("MOONS_FIXTURE_DONE ticks=" + ticks.invoke(minecraft));
    }

    /** Simulates Genesis: JDK classes are visible, arbitrary bootstrap packages are not. */
    private static final class GenesisLikeClassLoader extends URLClassLoader {
        private GenesisLikeClassLoader(URL fixtureJar) {
            super(new URL[] {fixtureJar}, null);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    if (name.startsWith("java.") || name.startsWith("jdk.")) {
                        loaded = super.loadClass(name, false);
                    } else if (name.startsWith("net.minecraft.")) {
                        loaded = findClass(name);
                    } else {
                        throw new ClassNotFoundException("Genesis-like loader rejected " + name);
                    }
                }
                if (resolve) resolveClass(loaded);
                return loaded;
            }
        }
    }
}
