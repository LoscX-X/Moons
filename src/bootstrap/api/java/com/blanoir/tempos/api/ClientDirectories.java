package com.blanoir.tempos.api;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;

/** Shared location for player-owned profiles and model data. */
public final class ClientDirectories {
    private ClientDirectories() {}

    private static Path applicationData() {
        String appData = System.getenv("APPDATA");
        return Path.of(appData == null || appData.isBlank() ? System.getProperty("user.home") : appData)
                .toAbsolutePath().normalize();
    }
    public static Path home() {
        String explicit = System.getProperty("tempos.home", "");
        if (explicit.isBlank()) explicit = System.getenv("TEMPOS_HOME");
        if (explicit != null && !explicit.isBlank()) return Path.of(explicit).toAbsolutePath().normalize();
        String previous = System.getProperty("moons.home", "");
        if (previous.isBlank()) previous = System.getenv("MOONS_HOME");
        Path appData = applicationData();
        if (previous != null && !previous.isBlank()) {
            Path chosen = Path.of(previous).toAbsolutePath().normalize();
            if (!chosen.equals(appData.resolve(".moons"))) return chosen;
        }
        return appData.resolve(".tempos");
    }
    public static Path config() {
        return importDefault(home().resolve("config"), Path.of("config"));
    }
    public static Path ysm(Path supplied) {
        Path relative = Path.of("data", "ysm");
        Path directory = supplied.toAbsolutePath().normalize();
        if (directory.equals(applicationData().resolve(".moons").resolve(relative)))
            directory = home().resolve(relative);
        return importDefault(directory, relative);
    }
    private static Path importDefault(Path directory, Path relative) {
        Path base = applicationData();
        Path previous = base.resolve(".moons").resolve(relative);
        if (!directory.equals(base.resolve(".tempos").resolve(relative)) || !Files.isDirectory(previous)) return directory;
        try {
            importDirectory(previous, directory);
            return directory;
        } catch (IOException e) {
            System.err.println("[Tempos] Using existing player data at " + previous + " (" + e.getMessage() + ")");
            return previous;
        }
    }

    static void importDirectory(Path previous, Path directory) throws IOException {
        Path marker = directory.resolve(".import-complete-v1");
        if (Files.isRegularFile(marker)) return;
        Files.walkFileTree(previous, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path source, BasicFileAttributes attributes) throws IOException {
                Files.createDirectories(directory.resolve(previous.relativize(source)));
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path source, BasicFileAttributes attributes) throws IOException {
                Path dest = directory.resolve(previous.relativize(source));
                if (dest.equals(marker)) return FileVisitResult.CONTINUE;
                if (attributes.isSymbolicLink() || attributes.isOther()) throw new IOException("Linked data at " + source);
                if (attributes.isRegularFile()) copyMissing(source, dest);
                return FileVisitResult.CONTINUE;
            }
        });
        try { Files.createFile(marker); }
        catch (FileAlreadyExistsException completedByAnotherClient) { /* Import already published. */ }
    }
    public static void copyMissing(Path source, Path target) throws IOException {
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return;
        Path temp = Files.createTempFile(target.getParent(), ".tempos-import-", ".tmp");
        try {
            Files.copy(source, temp, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            try { Files.move(temp, target); }
            catch (FileAlreadyExistsException existing) { /* Preserve the existing player's copy. */ }
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
