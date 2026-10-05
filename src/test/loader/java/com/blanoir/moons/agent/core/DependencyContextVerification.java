package com.blanoir.moons.agent.core;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;

/** Consumes the exact C# fixture, including Unicode paths, and rejects tampered contexts. */
public final class DependencyContextVerification {
    public static void main(String[] args) throws Exception {
        var fixture = new LinkedHashMap<String, String>();
        for (String line :
                Files.readAllLines(
                        Path.of(args[0], "dependency-fixture.properties"),
                        StandardCharsets.UTF_8)) {
            int split = line.indexOf('=');
            fixture.put(line.substring(0, split), line.substring(split + 1));
        }
        Path home = Path.of(fixture.get("home"));
        String manifest = fixture.get("manifest"),
                hash = fixture.get("hash"),
                game = fixture.get("profile");
        var context = DependencyContext.resolve(home, manifest, hash, game);
        require(
                Files.isRegularFile(context.ui()) && Files.isRegularFile(context.module()),
                "Missing resolved paths");
        reject(() -> DependencyContext.resolve(home, manifest, "0".repeat(64), game));
        reject(() -> DependencyContext.resolve(home, manifest, hash, "other-game"));
        Path file = Path.of(manifest);
        String original = Files.readString(file, StandardCharsets.UTF_8);
        for (String changed :
                java.util.List.of(
                        original + "format=2\n",
                        original.replace("java.minimum=25", "java.minimum=999"),
                        original.replace("ui=", "ui=../../../../"),
                        original.replace("root=.\n", "root=../\n"))) {
            Files.writeString(file, changed, StandardCharsets.UTF_8);
            reject(() -> DependencyContext.resolve(home, manifest, digest(changed), game));
        }
        Files.writeString(file, original, StandardCharsets.UTF_8);
        Path library = context.root().resolve("libraries/moons-ysm-core.jar");
        byte[] previous = Files.readAllBytes(library);
        Files.writeString(library, "corrupt");
        reject(() -> DependencyContext.resolve(home, manifest, hash, game));
        Files.write(library, previous);
        DependencyContext.resolve(home, manifest, hash, game);
        System.out.println(
                "MOONS_JAVA_DEPENDENCY_CONTEXT_VERIFIED CSharp-handoff profile hash class-version duplicate-key path-boundary corruption");
    }

    private static String digest(String text) throws Exception {
        return HexFormat.of()
                .formatHex(
                        MessageDigest.getInstance("SHA-256")
                                .digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    private interface Checked {
        void run() throws Exception;
    }

    private static void reject(Checked check) throws Exception {
        try {
            check.run();
        } catch (java.io.IOException expected) {
            return;
        }
        throw new AssertionError("Invalid dependency context was accepted");
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
