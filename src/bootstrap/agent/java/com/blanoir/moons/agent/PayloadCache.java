package com.blanoir.moons.agent;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/** Extracts nested JARs into a content-addressed temporary cache. */
public final class PayloadCache {
    private PayloadCache() {}

    public static Path extract(Path outerJar, String entryName, String fileName)
            throws IOException {
        try (JarFile jar = new JarFile(outerJar.toFile())) {
            JarEntry entry = jar.getJarEntry(entryName);
            if (entry == null) {
                throw new IOException("Missing embedded payload: " + entryName);
            }
            byte[] bytes;
            try (InputStream input = jar.getInputStream(entry)) {
                bytes = input.readAllBytes();
            }

            String digest = sha256(bytes);
            Path directory = Path.of(System.getProperty("java.io.tmpdir"), "moons", digest);
            Files.createDirectories(directory);
            Path target = directory.resolve(fileName);
            if (!Files.isRegularFile(target) || Files.size(target) != bytes.length) {
                Path temporary =
                        directory.resolve(fileName + ".tmp-" + ProcessHandle.current().pid());
                Files.write(temporary, bytes);
                try {
                    Files.move(
                            temporary,
                            target,
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException atomicMoveFailure) {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            return target;
        }
    }

    private static String sha256(byte[] bytes) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IOException("SHA-256 is not available", impossible);
        }
    }
}
