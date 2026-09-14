package com.blanoir.moons.ysm;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Model-local decoding and cache ownership. Audio output belongs to the version adapter. */
public final class YsmSoundBank implements AutoCloseable {
    public record Pcm(byte[] bytes, int rate, int channels) {}

    public interface Decoder {
        Pcm decode(byte[] data) throws Exception;
    }

    public interface Output {
        YsmSoundHandle play(Pcm pcm, boolean loop, float volume, float pitch) throws Exception;
    }

    private final Map<String, byte[]> sounds;
    private final Map<String, Pcm> decoded = new LinkedHashMap<>(16, .75f, true);
    private long decodedBytes;
    private final Set<Playing> active = ConcurrentHashMap.newKeySet();
    private final ExecutorService worker;
    private final Decoder decoder;
    private final Output output;
    private final Consumer<String> diagnostic;
    private volatile boolean closed;

    public YsmSoundBank(
            Map<String, byte[]> sounds,
            Decoder decoder,
            Output output,
            Consumer<String> diagnostic) {
        this.sounds = Map.copyOf(sounds);
        this.decoder = decoder;
        this.output = output;
        this.diagnostic = diagnostic;
        worker =
                Executors.newSingleThreadExecutor(
                        task -> {
                            Thread thread = new Thread(task, "Moons YSM audio");
                            thread.setDaemon(true);
                            return thread;
                        });
    }

    public YsmSoundHandle play(String name, boolean loop, float volume, float pitch) {
        for (Playing previous : List.copyOf(active)) previous.stopped();
        if (closed || active.size() >= 64 || !Float.isFinite(volume) || !Float.isFinite(pitch))
            return null;
        String key = sounds.containsKey(name) ? name : name + ".ogg";
        byte[] encoded = sounds.get(key);
        if (encoded == null) {
            diagnostic.accept("Sound not found: " + name);
            return null;
        }
        Playing playing = new Playing();
        active.add(playing);
        try {
            worker.submit(
                    () -> {
                        try {
                            if (closed || playing.stopped) {
                                playing.close();
                                return;
                            }
                            Pcm pcm = decoded.get(key);
                            if (pcm == null) {
                                pcm = decoder.decode(encoded);
                                if (pcm.bytes().length == 0
                                        || pcm.bytes().length > 128 * 1024 * 1024
                                        || pcm.rate() <= 0
                                        || pcm.channels() < 1
                                        || pcm.channels() > 2)
                                    throw new IllegalArgumentException("Invalid decoded audio");
                                while (!decoded.isEmpty()
                                        && decodedBytes + pcm.bytes().length > 256L * 1024 * 1024) {
                                    var first = decoded.entrySet().iterator();
                                    decodedBytes -= first.next().getValue().bytes().length;
                                    first.remove();
                                }
                                decoded.put(key, pcm);
                                decodedBytes += pcm.bytes().length;
                            }
                            synchronized (playing) {
                                if (closed || playing.stopped) return;
                                playing.delegate =
                                        output.play(
                                                pcm,
                                                loop,
                                                Math.clamp(volume, 0, 16),
                                                Math.clamp(pitch, .05f, 4));
                                if (playing.delegate == null) playing.close();
                            }
                        } catch (Exception error) {
                            playing.close();
                            diagnostic.accept("Sound " + name + ": " + error.getMessage());
                        }
                    });
        } catch (RejectedExecutionException shutdown) {
            playing.close();
        }
        return playing;
    }

    private final class Playing implements YsmSoundHandle {
        private YsmSoundHandle delegate;
        private volatile boolean stopped;

        public synchronized boolean stopped() {
            if (!stopped && delegate != null && delegate.stopped()) close();
            return stopped;
        }

        public synchronized void close() {
            if (stopped) return;
            stopped = true;
            active.remove(this);
            if (delegate != null) {
                delegate.close();
                delegate = null;
            }
        }
    }

    public void stopAll() {
        for (Playing sound : List.copyOf(active)) sound.close();
    }

    public void close() {
        closed = true;
        stopAll();
        worker.shutdownNow();
    }
}
