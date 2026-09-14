package com.blanoir.moons.api;

import java.util.List;
import java.util.Map;

/** Optional local model editing service. Commands run on the game thread; snapshots are immutable. */
public interface YsmStudio {
    record Parameter(
            String id,
            String group,
            String title,
            String description,
            String kind,
            double min,
            double max,
            double step,
            Map<String, String> labels) {
        public Parameter {
            labels = Map.copyOf(labels);
        }
    }

    record Animation(String id, String label, String group, double duration) {}

    record BonePose(
            double x,
            double y,
            double z,
            double pitch,
            double yaw,
            double roll,
            double scaleX,
            double scaleY,
            double scaleZ,
            boolean hidden) {}

    record Snapshot(
            String model,
            List<Parameter> parameters,
            Map<String, Double> values,
            List<Animation> animations,
            String animation,
            double time,
            double speed,
            boolean paused,
            List<String> textures,
            String texture,
            List<String> bones,
            Map<String, String> controllers,
            Map<String, Double> variables,
            List<String> diagnostics,
            String result,
            Map<String, BonePose> poses) {
        public Snapshot {
            parameters = List.copyOf(parameters);
            values = Map.copyOf(values);
            animations = List.copyOf(animations);
            textures = List.copyOf(textures);
            bones = List.copyOf(bones);
            controllers = Map.copyOf(controllers);
            variables = Map.copyOf(variables);
            diagnostics = List.copyOf(diagnostics);
            poses = Map.copyOf(poses);
        }
    }

    Snapshot studioSnapshot();

    void setParameter(String id, double value);

    void play(String animation);

    void playback(double speed, boolean paused);

    void seek(double seconds);

    void texture(String name);

    void evaluate(String expression);

    void resetParameters();

    void pose(String bone, BonePose pose);

    void resetPose();
}
