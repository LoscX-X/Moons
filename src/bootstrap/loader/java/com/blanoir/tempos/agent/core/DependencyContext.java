package com.blanoir.tempos.agent.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/** Validated, attempt-owned dependency paths. Never resolves through a mutable current pointer. */
record DependencyContext(Path ui, Path root, Path module, boolean temporary) {
    static DependencyContext resolve(Path home, String manifest, String expectedHash, String gameId)
            throws Exception {
        return resolve(home, null, manifest, expectedHash, gameId);
    }

    static DependencyContext resolve(
            Path home, Path outerJar, String manifest, String expectedHash, String gameId)
            throws Exception {
        Path context = plainPath(Path.of(manifest));
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
        boolean selected = "3".equals(values.get("format"));
        boolean temporary = "4".equals(values.get("format"));
        if ((!selected && !temporary && !"2".equals(values.get("format")))
                || !gameId.equals(values.get("profile")))
            throw new IOException("Dependency context profile does not match " + gameId);
        int minimum;
        try {
            minimum = Integer.parseInt(required(values, "java.minimum"));
        } catch (NumberFormatException invalid) {
            throw new IOException("Invalid dependency Java minimum", invalid);
        }
        if (minimum < 1 || Runtime.version().feature() < minimum)
            throw new IOException("Dependency profile requires Java " + minimum);
        if ((selected || temporary)
                && (!".".equals(required(values, "root"))
                        || !gameId.matches("[0-9]+(?:\\.[0-9]+)+(?:-(?:snapshot|pre|rc)-[0-9]+)?")))
            throw new IOException("Invalid selected dependency root/profile");
        Path root;
        Path uiRoot;
        if (temporary) {
            if (outerJar == null || context.getParent() == null
                    || !context.getParent().getFileName().toString().equals("contexts"))
                throw new IOException("Temporary dependency context requires its session payload");
            root = plainPath(context.getParent().getParent());
            if (!root.equals(plainPath(outerJar).getParent()))
                throw new IOException("Dependency context is outside the payload session");
            uiRoot = root;
        } else {
            Path realHome = home.toRealPath();
            if (!context.startsWith(realHome.resolve("installations"))
                    && !context.startsWith(realHome.resolve("libraries/latest"))
                    && !context.startsWith(realHome.resolve("libraries/legacy")))
                throw new IOException("Dependency context is outside installation views");
            root = selected ? plainPath(context.getParent().getParent())
                    : beneath(realHome, required(values, "root"));
            Path area = selected
                    ? (root.startsWith(realHome.resolve("libraries/latest"))
                            ? realHome.resolve("libraries/latest").resolve(gameId)
                            : realHome.resolve("libraries/legacy").resolve(gameId))
                    : realHome.resolve("installations");
            if (!root.startsWith(area) || !context.startsWith(root))
                throw new IOException("Dependency context does not belong to its installation view");
            uiRoot = selected ? root : realHome;
        }
        Path ui = beneath(uiRoot, required(values, "ui"));
        if ((selected || temporary) && !ui.startsWith(root))
            throw new IOException("Dependency UI does not belong to its selected package");
        verifyHash(Files.readAllBytes(ui), required(values, "ui.sha256"), ui);
        String moduleName = required(values, "module");
        if (!moduleName.matches("modules/tempos-ysm-[A-Za-z0-9._-]+\\.jar"))
            throw new IOException("Invalid dependency module path");
        for (String name :
                java.util.List.of(
                        "libraries/tempos-ysm-core.jar",
                        "libraries/tempos-ysm-codecs.jar",
                        "libraries/tempos-ysm-images.jar",
                        moduleName)) {
            Path file = beneath(root, name);
            verifyHash(Files.readAllBytes(file), required(values, name + ".sha256"), file);
        }
        return new DependencyContext(ui, root, beneath(root, moduleName), temporary);
    }

    private static String required(Map<String, String> values, String key) throws IOException {
        String value = values.get(key);
        if (value == null || value.isBlank())
            throw new IOException("Missing dependency context key: " + key);
        return value;
    }

    private static Path beneath(Path root, String relative) throws IOException {
        Path path = Path.of(relative);
        if (path.isAbsolute() || relative.contains(":"))
            throw new IOException("Absolute dependency reference");
        for (String part : relative.split("[/\\\\]", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals(".."))
                throw new IOException("Invalid relative dependency reference");
        }
        Path real = plainPath(root.resolve(path));
        if (!real.startsWith(root)) throw new IOException("Dependency reference escapes its root");
        return real;
    }

    /** Reject linked ancestors as well as linked leaf files, including Windows junctions. */
    private static Path plainPath(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        Path current = absolute.getRoot();
        for (Path part : absolute) {
            current = current.resolve(part);
            BasicFileAttributes attributes =
                    Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attributes.isSymbolicLink() || attributes.isOther()
                    || !current.equals(current.toRealPath()))
                throw new IOException("Linked dependency path: " + current);
        }
        return absolute;
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
