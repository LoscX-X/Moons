package com.blanoir.moons.agent.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/** Validated, attempt-owned dependency paths. Never resolves through a mutable current pointer. */
record DependencyContext(Path ui, Path root, Path module) {
    static DependencyContext resolve(Path home, String manifest, String expectedHash, String gameId)
            throws Exception {
        Path realHome = home.toRealPath();
        Path context = Path.of(manifest).toRealPath();
        if (!context.startsWith(realHome.resolve("installations")))
            throw new IOException("Dependency context is outside installation views");
        byte[] bytes = Files.readAllBytes(context);
        verifyHash(bytes, expectedHash, context);
        Map<String, String> values = new LinkedHashMap<>();
        for (String line : new String(bytes, StandardCharsets.UTF_8).split("\\R")) {
            if (line.isBlank()) continue;
            int equal = line.indexOf('=');
            if (equal <= 0
                    || values.putIfAbsent(line.substring(0, equal), line.substring(equal + 1))
                            != null) throw new IOException("Invalid dependency context line");
        }
        if (!"2".equals(values.get("format")) || !gameId.equals(values.get("profile")))
            throw new IOException("Dependency context profile does not match " + gameId);
        int minimum = Integer.parseInt(required(values, "java.minimum"));
        if (minimum < 1 || Runtime.version().feature() < minimum)
            throw new IOException("Dependency profile requires Java " + minimum);
        Path root = beneath(realHome, required(values, "root"));
        if (!root.startsWith(realHome.resolve("installations")) || !context.startsWith(root))
            throw new IOException("Dependency context does not belong to its installation view");
        Path ui = beneath(realHome, required(values, "ui"));
        verifyHash(Files.readAllBytes(ui), required(values, "ui.sha256"), ui);
        String moduleName = required(values, "module");
        if (!moduleName.matches("modules/moons-ysm-[A-Za-z0-9._-]+\\.jar"))
            throw new IOException("Invalid dependency module path");
        for (String name :
                java.util.List.of(
                        "libraries/moons-ysm-core.jar",
                        "libraries/moons-ysm-codecs.jar",
                        "libraries/moons-ysm-images.jar",
                        moduleName)) {
            Path file = beneath(root, name);
            verifyHash(Files.readAllBytes(file), required(values, name + ".sha256"), file);
        }
        return new DependencyContext(ui, root, beneath(root, moduleName));
    }

    private static String required(Map<String, String> values, String key) throws IOException {
        String value = values.get(key);
        if (value == null || value.isBlank())
            throw new IOException("Missing dependency context key: " + key);
        return value;
    }

    private static Path beneath(Path root, String relative) throws IOException {
        Path path = Path.of(relative);
        if (path.isAbsolute()) throw new IOException("Absolute dependency reference");
        Path real = root.resolve(path).normalize().toRealPath();
        if (!real.startsWith(root)) throw new IOException("Dependency reference escapes its root");
        return real;
    }

    private static void verifyHash(byte[] bytes, String expected, Path file) throws Exception {
        if (expected == null
                || !expected.matches("[a-f0-9]{64}")
                || !HexFormat.of()
                        .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
                        .equals(expected))
            throw new IOException("Dependency SHA-256 mismatch: " + file.getFileName());
    }
}
