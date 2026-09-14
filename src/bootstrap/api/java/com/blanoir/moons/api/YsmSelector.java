package com.blanoir.moons.api;

import java.util.List;

/** Local selection only. Immutable snapshots never expose a model or a version-specific game type. */
public interface YsmSelector {
    enum State {
        VANILLA,
        LOADING,
        ACTIVE,
        ERROR
    }

    record Model(String id, String name, String format) {}

    record Snapshot(
            String directory,
            List<Model> models,
            String requested,
            String active,
            State state,
            String message) {
        public Snapshot {
            models = List.copyOf(models);
        }
    }

    Snapshot snapshot();

    void select(String modelId);

    void refresh();

    void reset();
}
