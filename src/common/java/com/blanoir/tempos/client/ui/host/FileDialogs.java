package com.blanoir.tempos.client.ui.host;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Blocking native choices; callers run these operations off the render thread. */
public final class FileDialogs {
    private FileDialogs() {}

    public static Path directory(String initial) throws IOException {
        return choose("folder", initial);
    }

    public static Path openJson() throws IOException {
        return choose("open-json", "");
    }

    public static Path saveJson() throws IOException {
        return choose("save-json", "");
    }

    private static Path choose(String operation, String initial) throws IOException {
        String configured = System.getProperty("tempos.dialogs.helper", "");
        if (configured.isBlank()) throw new IOException("Native file dialog helper is unavailable.");
        Path helper = Path.of(configured).toAbsolutePath().normalize();
        if (!Files.isRegularFile(helper)) throw new IOException("Native file dialog helper is unavailable.");
        Path result = helper.getParent().resolve(".dialog-" + UUID.randomUUID().toString().replace("-", "") + ".txt");
        var args = new ArrayList<>(List.of(helper.toString(), "--native-dialog", operation,
                "--initial", initial, "--result", result.toString(),
                "--parent-pid", Long.toString(ProcessHandle.current().pid()),
                "--parent-started", Long.toString(ProcessHandle.current().info().startInstant()
                        .map(java.time.Instant::toEpochMilli).orElse(0L))));
        Process process = new ProcessBuilder(args).redirectErrorStream(true).start();
        try {
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            int exit = process.waitFor();
            if (exit != 0) throw new IOException(output.isEmpty() ? "Could not open the file dialog." : output);
            if (!Files.isRegularFile(result)) return null;
            String selected = Files.readString(result, StandardCharsets.UTF_8).trim();
            return selected.isEmpty() ? null : Path.of(selected).toAbsolutePath().normalize();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            process.destroy();
            throw new IOException("File selection was interrupted.", interrupted);
        } finally {
            Files.deleteIfExists(result);
        }
    }
}
