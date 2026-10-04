package com.blanoir.moons.runtime;

import java.lang.reflect.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;

/** Tests the real named and obfuscated Minecraft version getter without a window or constructor. */
public final class MinecraftVersionGuardVerification {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        var unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
        List<URL> libraries = new ArrayList<>();
        for (int i = 1; i < args.length; i++) libraries.add(Path.of(args[i]).toUri().toURL());
        for (boolean named : List.of(true, false)) {
            List<URL> urls = new ArrayList<>(libraries);
            urls.addFirst(
                    root.resolve(named ? "minecraft-1.8.9-named.jar" : "client.jar")
                            .toUri()
                            .toURL());
            try (URLClassLoader loader =
                    new URLClassLoader(
                            urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader())) {
                Class<?> type = loader.loadClass(named ? "net.minecraft.client.Minecraft" : "ave");
                Object instance = unsafe.allocateInstance(type);
                String fieldName = named ? "launchedVersion" : obfuscatedVersionField(root);
                Field field = type.getDeclaredField(fieldName);
                field.setAccessible(true);
                field.set(instance, "1.8.9");
                MinecraftVersionGuard.verify(instance, "1.8.9");
                field.set(instance, "1.8.8");
                try {
                    MinecraftVersionGuard.verify(instance, "1.8.9");
                    throw new AssertionError("Incorrect launcher version was accepted");
                } catch (IllegalStateException expected) {
                    if (!expected.getMessage().contains("1.8.8")) throw expected;
                }
                System.out.println(
                        "MINECRAFT189_VERSION_GUARD namespace="
                                + (named ? "named" : "obfuscated")
                                + " real-getter+core-fingerprint+wrong-label=passed");
            }
        }
    }

    private static String obfuscatedVersionField(Path root) throws Exception {
        String srg = null;
        for (String line : Files.readAllLines(root.resolve("fields.csv"))) {
            String[] p = line.split(",", 3);
            if (p.length > 1 && p[1].equals("launchedVersion")) srg = p[0];
        }
        for (String line : Files.readAllLines(root.resolve("joined.srg"))) {
            String[] p = line.split(" ");
            if (p[0].equals("FD:") && p[2].equals("net/minecraft/client/Minecraft/" + srg))
                return p[1].substring(p[1].lastIndexOf('/') + 1);
        }
        throw new AssertionError("Missing launchedVersion field mapping");
    }
}
