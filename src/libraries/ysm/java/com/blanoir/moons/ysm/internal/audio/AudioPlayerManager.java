package com.blanoir.moons.ysm.internal.audio;

import com.blanoir.moons.ysm.internal.geckolib3.core.AnimatableEntity;

import java.util.HashMap;
import java.util.Map;

/** Tracks ownership of sounds; the host supplies the actual audio backend. */
public final class AudioPlayerManager {
    private final Map<Integer, com.blanoir.moons.ysm.YsmSoundHandle> playing = new HashMap<>();
    private final java.util.List<com.blanoir.moons.ysm.YsmSoundHandle> unkeyed =
            new java.util.ArrayList<>();

    public void playSound(
            AnimatableEntity<?> entity, int key, String name, boolean replace, Object options) {
        play(entity, key, name, false, 1, 1, replace);
    }

    public boolean play(
            AnimatableEntity<?> entity,
            int key,
            String name,
            boolean loop,
            float volume,
            float pitch,
            boolean replace) {
        tick();
        if (key != 0 && playing.containsKey(key)) {
            if (!replace) return false;
            stop(key);
        }
        var handle = entity.runtime().effects().sound(name, loop, volume, pitch);
        if (handle != null) {
            if (key == 0) unkeyed.add(handle);
            else playing.put(key, handle);
        }
        return handle != null;
    }

    public boolean stopSound(int key) {
        boolean existed = playing.containsKey(key);
        stop(key);
        return existed;
    }

    public void stop(int key) {
        AutoCloseable sound = playing.remove(key);
        if (sound != null)
            try {
                sound.close();
            } catch (Exception ignored) {
            }
    }

    public void stopAll() {
        for (int key : java.util.List.copyOf(playing.keySet())) stop(key);
        unkeyed.forEach(com.blanoir.moons.ysm.YsmSoundHandle::close);
        unkeyed.clear();
    }

    public void tick() {
        playing.values().removeIf(com.blanoir.moons.ysm.YsmSoundHandle::stopped);
        unkeyed.removeIf(com.blanoir.moons.ysm.YsmSoundHandle::stopped);
    }
}
