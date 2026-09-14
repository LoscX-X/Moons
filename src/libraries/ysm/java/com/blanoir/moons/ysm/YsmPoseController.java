package com.blanoir.moons.ysm;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Per-model pose editing and persistence, independent of rendering APIs. */
public final class YsmPoseController {
    private final LocalYsmModel model;
    private final Path file;
    private final Map<String, YsmPose> values = new LinkedHashMap<>();
    private boolean dirty;

    public YsmPoseController(LocalYsmModel model, Path file) throws IOException {
        this.model = model;
        this.file = file;
        if (file != null && Files.isRegularFile(file)) {
            try (var reader = Files.newBufferedReader(file)) {
                Map<String, YsmPose> saved =
                        new Gson()
                                .fromJson(
                                        reader, new TypeToken<Map<String, YsmPose>>() {}.getType());
                if (saved != null)
                    saved.forEach(
                            (bone, pose) -> {
                                if (pose != null && model.runtime().bones().containsKey(bone))
                                    set(bone, pose);
                            });
                dirty = false;
            } catch (RuntimeException error) {
                model.runtime().diagnostic("Could not read saved pose: " + error.getMessage());
            }
        }
    }

    public void set(String bone, YsmPose pose) {
        if (!model.runtime().bones().containsKey(bone))
            throw new IllegalArgumentException("Unknown bone: " + bone);
        if (pose == null || pose.equals(YsmPose.IDENTITY)) values.remove(bone);
        else values.put(bone, pose);
        model.poseOverrides(values);
        dirty = true;
    }

    public void reset() {
        values.clear();
        model.poseOverrides(values);
        dirty = true;
    }

    public Map<String, YsmPose> values() {
        return Map.copyOf(values);
    }

    public void save() throws IOException {
        if (!dirty || file == null) return;
        Files.createDirectories(file.getParent());
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temp, new Gson().toJson(values));
        try {
            Files.move(
                    temp,
                    file,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException error) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
        dirty = false;
    }
}
