package com.blanoir.moons.client.utils.render;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** A single completion path owns all temporary GPU resources, including failed readbacks. */
public final class NativeIconCaptureResult {
    private final Consumer<byte[]> ready;
    private final List<AutoCloseable> resources = new ArrayList<>();
    private boolean completed;

    public NativeIconCaptureResult(Consumer<byte[]> ready) {
        this.ready = ready;
    }

    public <T extends AutoCloseable> T own(T resource) {
        if (completed) throw new IllegalStateException("Capture already completed");
        resources.add(resource);
        return resource;
    }

    /** Null pixels mean failure; callers must release their busy state in either case. */
    public void complete(byte[] pixels) {
        if (completed) return;
        completed = true;
        for (int i = resources.size() - 1; i >= 0; i--) {
            try {
                resources.get(i).close();
            } catch (Exception ignored) {
                /* Release remaining owners. */
            }
        }
        resources.clear();
        ready.accept(pixels);
    }
}
