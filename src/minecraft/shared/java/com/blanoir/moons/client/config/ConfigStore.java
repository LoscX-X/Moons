package com.blanoir.moons.client.config;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

/** Publishes a complete properties snapshot, retaining the previous file on write failure. */
final class ConfigStore {
    private ConfigStore() {}

    static void write(Path target, Properties values) throws IOException {
        Files.createDirectories(target.getParent());
        Path staging = Files.createTempFile(target.getParent(), target.getFileName() + ".", ".tmp");
        try {
            try (OutputStream output = Files.newOutputStream(staging)) {
                values.store(output, "client settings");
            }
            try {
                Files.move(
                        staging,
                        target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(staging);
        }
    }
}
