package com.blanoir.tempos.agent.core;

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
        Path payload = fixture.containsKey("payload") ? Path.of(fixture.get("payload")) : null;
        var context = DependencyContext.resolve(home, payload, manifest, hash, game);
        require(
                Files.isRegularFile(context.ui()) && Files.isRegularFile(context.module()),
                "Missing resolved paths");
        reject(() -> DependencyContext.resolve(home, payload, manifest, "0".repeat(64), game));
        reject(() -> DependencyContext.resolve(home, payload, manifest, hash, "other-game"));
        Path file = Path.of(manifest);
        String original = Files.readString(file, StandardCharsets.UTF_8);
        for (String changed :
                java.util.List.of(
                        original + "format=2\n",
                        original.replace("java.minimum=25", "java.minimum=999"),
                        original.replace("ui=", "ui=../../../../"),
                        original.replace("root=.\n", "root=../\n"))) {
            Files.writeString(file, changed, StandardCharsets.UTF_8);
            reject(() -> DependencyContext.resolve(home, payload, manifest, digest(changed), game));
        }
        Files.writeString(file, original, StandardCharsets.UTF_8);
        Path library = context.root().resolve("libraries/tempos-ysm-core.jar");
        byte[] previous = Files.readAllBytes(library);
        Files.writeString(library, "corrupt");
        reject(() -> DependencyContext.resolve(home, payload, manifest, hash, game));
        Files.write(library, previous);
        DependencyContext.resolve(home, payload, manifest, hash, game);
        verifyTemporary(Path.of(args[0]));
        System.out.println(
                "TEMPOS_JAVA_DEPENDENCY_CONTEXT_VERIFIED CSharp-handoff profile hash class-version duplicate-key path-boundary corruption temporary-session");
    }

    private static void verifyTemporary(Path fixtures) throws Exception {
        Path session = Files.createTempDirectory(fixtures, "temporary-dependencies-").toRealPath();
        Path home = session.resolveSibling(session.getFileName() + "-user-data");
        Path payload = session.resolve("tempos.jar");
        Files.writeString(payload, "payload fixture");
        var text = new StringBuilder("format=4\nprofile=26.1.2\njava.minimum=25\nroot=.\n");
        text.append("ui=ui/tempos-ui-runtime.jar\nui.sha256=").append(digest("ui")).append('\n');
        text.append("module=modules/tempos-ysm-26.1.2.jar\n");
        Path ui = session.resolve("ui/tempos-ui-runtime.jar");
        Files.createDirectories(ui.getParent());
        Files.writeString(ui, "ui");
        for (String name : java.util.List.of("libraries/tempos-ysm-core.jar",
                "libraries/tempos-ysm-codecs.jar", "libraries/tempos-ysm-images.jar",
                "modules/tempos-ysm-26.1.2.jar")) {
            Path file = session.resolve(name);
            Files.createDirectories(file.getParent());
            Files.writeString(file, name);
            text.append(name).append(".sha256=").append(digest(name)).append('\n');
        }
        Path context = session.resolve("contexts/26_1.properties");
        Files.createDirectories(context.getParent());
        String original = text.toString();
        Files.writeString(context, original);
        var resolved = DependencyContext.resolve(home, payload, context.toString(), digest(original), "26.1.2");
        require(resolved.temporary() && resolved.root().equals(session), "Temporary session was not resolved");
        require(!Files.exists(home), "Dependency resolution created a persistent installation");
        Path otherPayload = session.resolveSibling("foreign-payload.jar");
        Files.writeString(otherPayload, "foreign fixture");
        try {
            reject(() -> DependencyContext.resolve(home, otherPayload, context.toString(), digest(original), "26.1.2"));
            for (String changed : java.util.List.of(original.replace("root=.\n", "root=../\n"),
                    original.replace("ui=ui/", "ui=../ui/"),
                    original.replace("ui=ui/", "ui=" + session.resolve("ui") + "/"))) {
                Files.writeString(context, changed);
                reject(() -> DependencyContext.resolve(home, payload, context.toString(), digest(changed), "26.1.2"));
            }
            Files.writeString(context, original);
            Path linked = session.resolve("ui-linked");
            try {
                Files.createSymbolicLink(linked, session.resolve("ui"));
            } catch (UnsupportedOperationException | java.nio.file.FileSystemException unavailable) {
                if (!System.getProperty("os.name").startsWith("Windows")) throw unavailable;
                Process junction = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J",
                        linked.toString(), session.resolve("ui").toString())
                        .redirectErrorStream(true).start();
                String output = new String(junction.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                require(junction.waitFor() == 0, "Junction fixture creation failed: " + output);
            }
            String changed = original.replace("ui=ui/", "ui=ui-linked/");
            Files.writeString(context, changed);
            reject(() -> DependencyContext.resolve(home, payload, context.toString(), digest(changed), "26.1.2"));
        } finally {
            Files.writeString(context, original);
            Files.deleteIfExists(session.resolve("ui-linked"));
            Files.deleteIfExists(otherPayload);
        }
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
