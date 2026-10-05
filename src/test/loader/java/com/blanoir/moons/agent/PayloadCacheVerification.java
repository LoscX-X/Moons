package com.blanoir.moons.agent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

public final class PayloadCacheVerification {
    public static void main(String[] args) throws Exception {
        Path fixture = Files.createTempDirectory(Path.of(args[0]), "payload-cache-");
        Path home = fixture.resolve("home");
        Path outer = fixture.resolve("outer.jar");
        byte[] content = "runtime fixture".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try (var output = new JarOutputStream(Files.newOutputStream(outer))) {
            output.putNextEntry(new JarEntry("runtime.jar"));
            output.write(content);
            output.closeEntry();
        }
        Path cached = PayloadCache.extract(outer, home, "runtime.jar", "moons-runtime.jar");
        if (!cached.startsWith(home.resolve("cache/runtime"))
                || !Arrays.equals(content, Files.readAllBytes(cached))) {
            throw new AssertionError(
                    "Runtime extraction escaped MOONS_HOME or changed the payload");
        }
        var identity = Files.readAttributes(cached, java.nio.file.attribute.BasicFileAttributes.class).fileKey();
        Files.setLastModifiedTime(cached, java.nio.file.attribute.FileTime.from(java.time.Instant.EPOCH));
        if (!cached.equals(PayloadCache.extract(outer, home, "runtime.jar", "moons-runtime.jar"))
                || !java.util.Objects.equals(identity, Files.readAttributes(cached, java.nio.file.attribute.BasicFileAttributes.class).fileKey())
                || !Arrays.equals(content, Files.readAllBytes(cached))
                || Files.getLastModifiedTime(cached).toInstant().equals(java.time.Instant.EPOCH)) {
            throw new AssertionError("Unchanged runtime cache was recreated");
        }
        System.out.println("PAYLOAD_CACHE_VERIFIED home-local extraction cache reuse identity and latest-use timestamp");
    }
}
