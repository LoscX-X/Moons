package com.blanoir.tempos.ysm.adapter;

import com.blanoir.tempos.api.ClientDirectories;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/** Parameter, pose and texture files belong to the selected model's canonical path. */
final class ModelPreferences {
    private ModelPreferences() {}

    static Path model(Path directory, String stored) {
        Path path = directory.resolve(stored).toAbsolutePath().normalize();
        Path legacy = previousDirectory(directory);
        if (legacy != null && path.startsWith(legacy)) {
            Path imported = directory.resolve(legacy.relativize(path));
            if (Files.isRegularFile(imported)) return imported;
        }
        return path;
    }
    static Path file(Path directory, Path model) throws IOException {
        Path path = model.toAbsolutePath().normalize();
        Path parameters = directory.resolve("parameters");
        String filename = key(path) + ".json";
        Path legacy = previousDirectory(directory);
        if (legacy != null && path.startsWith(directory) && Files.isDirectory(parameters)) {
            String oldName = key(legacy.resolve(directory.relativize(path))) + ".json";
            try (var files = Files.newDirectoryStream(parameters, oldName + "*")) {
                for (Path source : files) {
                    String name = source.getFileName().toString();
                    if (!name.endsWith(".tmp") && Files.isRegularFile(source))
                        ClientDirectories.copyMissing(source, parameters.resolve(filename + name.substring(oldName.length())));
                }
            }
        }
        return parameters.resolve(filename);
    }
    private static String key(Path path) {
        return UUID.nameUUIDFromBytes(path.toString().getBytes(StandardCharsets.UTF_8)).toString();
    }
    private static Path previousDirectory(Path directory) {
        return directory.endsWith(Path.of(".tempos", "data", "ysm"))
                ? directory.getParent().getParent().resolveSibling(".moons").resolve("data").resolve("ysm") : null;
    }
}
